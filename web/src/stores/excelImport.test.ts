import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { applyExcelAiCategoryRevision } from '@/api/product'
import { useExcelImportStore } from './excelImport'

vi.mock('@/api/product', () => ({
  applyExcelAiCategoryRevision: vi.fn()
}))

describe('Excel 商品取消导入', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('只统计仍将导入且没有产品等级的行', () => {
    const store = useExcelImportStore()
    store.confirmedMapping = { 产品定位: 'productLevel' }
    store.previewData = [
      { rowIndex: 3, rawValues: { 产品定位: 'B' }, mappedFieldByHeader: {}, images: [] },
      { rowIndex: 4, rawValues: {}, mappedFieldByHeader: {}, images: [] },
      { rowIndex: 5, rawValues: {}, mappedFieldByHeader: {}, images: [] },
      { rowIndex: 6, systemExcluded: true, rawValues: {}, mappedFieldByHeader: {}, images: [] }
    ]
    store.setRowsSkipped([5], true)
    expect(store.missingProductLevelRowCount).toBe(1)
    store.updatePreviewEdit(4, '产品定位', 'C')
    expect(store.missingProductLevelRowCount).toBe(0)
  })

  it('取消整件商品时跳过全部变体，恢复时保留原品类', async () => {
    const store = useExcelImportStore()
    store.currentStep = 3
    store.categoryMode = 'SINGLE'
    store.categoryHint = 'OF'
    store.mappingResponse = {
      batchId: 'EAI-1', headers: [], suggestedMapping: {}, previewRows: [], priceColumns: []
    }
    store.previewData = [3, 4, 7].map(rowIndex => ({ rowIndex, rawValues: {}, mappedFieldByHeader: {}, images: [] }))
    store.rowCategorySelections = { 3: 'OF', 4: 'OF', 7: 'OF' }
    store.toggleSkipRow(3)
    store.setRowsSkipped([3, 4], true)
    vi.mocked(applyExcelAiCategoryRevision).mockResolvedValue({
      batchId: 'EAI-1', previewRevision: 2, rows: [{ rowIndex: 7, finalCategoryCode: 'OF' }]
    })

    expect(await store.handleGoToConfirmStep()).toBe(true)
    expect(applyExcelAiCategoryRevision).toHaveBeenCalledWith('EAI-1', expect.objectContaining({ skipRows: [3, 4] }))
    expect(store.skippedRows).toEqual(new Set([3, 4]))
    store.setRowsSkipped([3, 4], false)
    expect(store.skippedRows.size).toBe(0)
    expect(store.rowCategorySelections).toEqual({ 3: 'OF', 4: 'OF', 7: 'OF' })
    expect(store.categoryRevisionDirty).toBe(true)
  })

  it('没有剩余可导入的商品时停留在清洗页', async () => {
    const store = useExcelImportStore()
    store.currentStep = 3
    store.previewData = [
      { rowIndex: 3, rawValues: {}, mappedFieldByHeader: {}, images: [] },
      { rowIndex: 4, systemExcluded: true, rawValues: {}, mappedFieldByHeader: {}, images: [] }
    ]
    store.setRowsSkipped([3], true)

    expect(await store.handleGoToConfirmStep()).toBe(false)
    expect(store.currentStep).toBe(3)
    expect(store.errorMessage).toContain('没有可导入的商品')
    expect(applyExcelAiCategoryRevision).not.toHaveBeenCalled()
  })
})
