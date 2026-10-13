import { describe, expect, it } from 'vitest'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { arrangeFurnitureItems, FURNITURE_ARRANGEMENT_COUNTS, MANUAL_FURNITURE_TEMPLATES, furnitureBounds, furnitureCatalogTemplates, furnitureInsideRoom, furnitureOverlap, newFurnitureItem } from './manualFurniture'
import ManualFurnitureGlyph from '@/components/floorplan/ManualFurnitureGlyph.vue'
import type { ManualLayoutRoom } from '@/types/floorPlan'

const room: ManualLayoutRoom = {
  clientId: 'room-1', label: '办公室', roomType: 'OFFICE',
  parts: [{ id: 'part-1', x: 1000, y: 1000, w: 4000, h: 3000 }],
  placementZone: null
}

describe('manual furniture templates', () => {
  it('offers 40 distinct office objects including the reference desk and cabinet variants', () => {
    expect(MANUAL_FURNITURE_TEMPLATES).toHaveLength(40)
    const ids = new Set(MANUAL_FURNITURE_TEMPLATES.map(item => item.id))
    expect(ids.size).toBe(40)
    for (const id of ['desk-l-rounded', 'desk-manager-double', 'desk-executive-l-double', 'cabinet-short']) {
      expect(ids.has(id)).toBe(true)
    }
    expect(MANUAL_FURNITURE_TEMPLATES.every(item => item.widthMm >= 100 && item.depthMm >= 100)).toBe(true)
  })

  it('shows only front desks in the former workstation category while retaining old layout templates', async () => {
    expect(furnitureCatalogTemplates).toHaveLength(37)
    expect(furnitureCatalogTemplates.filter(item => item.category === '前台').map(item => item.id))
      .toEqual(['counter-straight', 'counter-l'])
    for (const id of ['desk-u', 'desk-training', 'desk-standing']) {
      expect(furnitureCatalogTemplates.some(item => item.id === id)).toBe(false)
      const template = MANUAL_FURNITURE_TEMPLATES.find(item => item.id === id)!
      const glyph = await renderToString(createSSRApp({ render: () => h(ManualFurnitureGlyph, {
        item: newFurnitureItem(template, room.clientId, 2500, 2200)
      }) }))
      expect(glyph).toContain('manual-furniture-glyph')
    }
  })

  it('recalculates occupied bounds after a quarter turn', () => {
    const desk = newFurnitureItem(MANUAL_FURNITURE_TEMPLATES[0]!, room.clientId, 2500, 2200)
    expect(furnitureBounds(desk)).toMatchObject({ w: 1200, h: 600 })
    expect(furnitureBounds({ ...desk, rotationDeg: 90 })).toMatchObject({ w: 600, h: 1200 })
  })

  it('checks room containment and overlap from real dimensions', () => {
    const desk = newFurnitureItem(MANUAL_FURNITURE_TEMPLATES[0]!, room.clientId, 2500, 2200)
    expect(furnitureInsideRoom(desk, room)).toBe(true)
    expect(furnitureInsideRoom({ ...desk, xMm: 4800 }, room)).toBe(false)
    expect(furnitureOverlap(desk, { ...desk, xMm: 3000 })).toBe(true)
    expect(furnitureOverlap(desk, { ...desk, xMm: 4000 })).toBe(false)
  })

  it('keeps cabinet height and L-desk wing as independent editable parameters', () => {
    const cabinet = MANUAL_FURNITURE_TEMPLATES.find(item => item.id === 'cabinet-short')!
    const roundedDesk = MANUAL_FURNITURE_TEMPLATES.find(item => item.id === 'desk-l-rounded')!
    expect(newFurnitureItem(cabinet, room.clientId, 2500, 2200).heightMm).toBe(900)
    expect(newFurnitureItem(roundedDesk, room.clientId, 2500, 2200).wingWidthMm).toBe(550)
    expect(cabinet.heightMinMm).toBe(401)
    expect(cabinet.heightMaxMm).toBe(1500)
  })

  it('renders reference-specific SVG lines from the same component used on the canvas', async () => {
    const render = (templateId: string) => {
      const template = MANUAL_FURNITURE_TEMPLATES.find(item => item.id === templateId)!
      return renderToString(createSSRApp({ render: () => h(ManualFurnitureGlyph, {
        item: newFurnitureItem(template, room.clientId, 2500, 2200)
      }) }))
    }
    expect(await render('desk-straight')).toContain('reference-guide')
    expect(await render('desk-l-rounded')).toContain(' Q ')
    expect((await render('cabinet-low')).match(/<path /g)).toHaveLength(3)
    expect((await render('cabinet-short')).match(/<line /g)).toHaveLength(5)
    expect((await render('cabinet-high')).match(/<path /g)).toHaveLength(3)
  })

  it('rearranges only the chosen existing furniture without changing products, sizes or rotation', () => {
    const selected = [
      { ...newFurnitureItem(MANUAL_FURNITURE_TEMPLATES[0]!, room.clientId, 3000, 2500), id: 'desk' },
      { ...newFurnitureItem(MANUAL_FURNITURE_TEMPLATES[15]!, room.clientId, 3500, 2600), id: 'chair', rotationDeg: 90 as const }
    ]
    const arranged = arrangeFurnitureItems(selected, 'pair', 50)
    expect(arranged).toHaveLength(2)
    expect(arranged.map(item => item.id)).toEqual(['desk', 'chair'])
    expect(arranged.map(({ xMm: _xMm, yMm: _yMm, ...rest }) => rest))
      .toEqual(selected.map(({ xMm: _xMm, yMm: _yMm, ...rest }) => rest))
    expect(arranged[0]!.yMm).toBeLessThan(arranged[1]!.yMm)
    expect(furnitureOverlap(arranged[0]!, arranged[1]!)).toBe(false)
    expect(selected[0]!.xMm).toBe(3000)
  })

  it('uses exactly the selected count and keeps each arrangement clear of self-overlap', () => {
    for (const arrangement of ['island', 'meeting', 'reception'] as const) {
      const count = FURNITURE_ARRANGEMENT_COUNTS[arrangement]
      const selected = Array.from({ length: count }, (_, index) => ({
        ...newFurnitureItem(MANUAL_FURNITURE_TEMPLATES[index]!, room.clientId, 4000 + index * 50, 3000),
        id: `item-${index}`
      }))
      const arranged = arrangeFurnitureItems(selected, arrangement, 500)
      expect(arranged).toHaveLength(count)
      for (let first = 0; first < arranged.length; first++) {
        for (let second = first + 1; second < arranged.length; second++) {
          expect(furnitureOverlap(arranged[first]!, arranged[second]!)).toBe(false)
        }
      }
    }
    expect(arrangeFurnitureItems([], 'pair', 50)).toEqual([])
  })
})
