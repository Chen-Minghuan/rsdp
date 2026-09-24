import { describe, expect, it } from 'vitest'
import { buildSixDimCategoryNameMap, buildSixDimCategoryOptions } from './sixDimCategories'

describe('sixDimCategories', () => {
  it('includes legacy and feature-flagged categories while displaying Chinese names only', () => {
    const names = buildSixDimCategoryNameMap(
      [
        { dictCode: 'SF', dictName: '沙发' },
        { dictCode: 'DK', dictName: '书桌/写字台' },
        { dictCode: 'MT', dictName: '床垫' }
      ],
      [
        { categoryCode: 'SF', categoryName: '沙发' },
        { categoryCode: 'DK', categoryName: 'DK' },
        { categoryCode: 'GENERIC', categoryName: '通用' }
      ]
    )

    expect(buildSixDimCategoryOptions(names)).toEqual([
      { label: '沙发', value: 'SF' },
      { label: '书桌/写字台', value: 'DK' },
      { label: '床垫', value: 'MT' }
    ])
  })

  it('uses a named schema as fallback without exposing code-only or generic schemas', () => {
    const names = buildSixDimCategoryNameMap(
      [{ dictCode: 'SF', dictName: '沙发' }],
      [
        { categoryCode: 'CW', categoryName: '窗帘/窗饰' },
        { categoryCode: 'PD', categoryName: 'PD' },
        { categoryCode: 'GENERIC', categoryName: '通用' }
      ]
    )

    expect([...names.entries()]).toEqual([
      ['SF', '沙发'],
      ['CW', '窗帘/窗饰']
    ])
  })
})
