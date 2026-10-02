import { expression, latest } from '@maplibre/maplibre-gl-style-spec'
import { describe, expect, it } from 'vitest'
import { englishLabel, usesNameField } from './labels'

function label(properties: Record<string, string>): string {
  const parsed = expression.createExpression(englishLabel, 'layers[0].layout.text-field',
    latest.layout_symbol['text-field'] as never)
  if (parsed.result !== 'success') throw new Error(JSON.stringify(parsed.value))
  return String(parsed.value.evaluate({ zoom: 12 }, { properties } as never))
}

describe('englishLabel', () => {
  it('prefers name:en', () => {
    expect(label({ name: 'Kandy', 'name:en': 'Kandy City' })).toBe('Kandy City')
  })

  it('uses a Latin local name', () => {
    expect(label({ name: 'Nugegoda' })).toBe('Nugegoda')
  })

  it('falls back to a Latin transliteration', () => {
    expect(label({ name: 'மணலாறு', script: 'Tamil', name2: 'Manalaaru' })).toBe('Manalaaru')
  })

  it('skips a name:en that is not in Latin script (OSM tagging mistakes)', () => {
    expect(label({ name: 'SUB POST OFFICE', 'name:en': 'உப தபாலகம்' })).toBe('SUB POST OFFICE')
    expect(label({ 'name:en': 'ලය විරේක මධ්‍යස්ථානය' })).toBe('')
  })

  it('skips a local name with non-Latin text even without a script tag', () => {
    expect(label({ name: 'Hospital மருத்துவமனை' })).toBe('')
  })

  it('keeps Latin names with accents and typographic punctuation', () => {
    expect(label({ name: 'Galle Face – “Green”' })).toBe('Galle Face – “Green”')
    expect(label({ name: 'Ālutgama' })).toBe('Ālutgama')
  })

  it('hides a label with no English or Latin name', () => {
    expect(label({ name: 'උප්පුකඩල්', script: 'Sinhala' })).toBe('')
    expect(label({ name: 'உப்புகடல்', script: 'Tamil', name2: 'උප්පුකඩල්', script2: 'Sinhala' })).toBe('')
  })
})

describe('usesNameField', () => {
  it('spots name-based text fields only', () => {
    expect(usesNameField(['get', 'name'])).toBe(true)
    expect(usesNameField(['get', 'ref'])).toBe(false)
    expect(usesNameField(undefined)).toBe(false)
  })
})
