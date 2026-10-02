import type { ExpressionSpecification } from 'maplibre-gl'

// MapLibre can't shape Sinhala or Tamil, so labels are English or Latin only.
// Protomaps tiles split a name into `name`, `name2`, `name3` and set `script`,
// `script2`, `script3` when that part is not in Latin script. A few OSM
// features also have non-Latin text in `name:en`, so every candidate must
// also start and end with a Latin letter or common punctuation.
// Order: name:en, then the first Latin part; with none, the label is hidden.

/** A one-character string is Latin (incl. accents) or general punctuation such as – ’ … */
function latinChar(char: ExpressionSpecification): ExpressionSpecification {
  return ['any', ['<', char, 'ɐ'], ['all', ['>=', char, ' '], ['<', char, '⁰']]]
}

function latin(key: string, scriptKey?: string): ExpressionSpecification {
  const value: ExpressionSpecification = ['get', key]
  return [
    'all',
    ['has', key],
    ...(scriptKey ? [['!', ['has', scriptKey]] as ExpressionSpecification] : []),
    latinChar(['slice', ['to-string', value], 0, 1]),
    latinChar(['slice', ['to-string', value], -1]),
  ]
}

export const englishLabel: ExpressionSpecification = [
  'case',
  latin('name:en'), ['get', 'name:en'],
  latin('name', 'script'), ['get', 'name'],
  latin('name2', 'script2'), ['get', 'name2'],
  latin('name3', 'script3'), ['get', 'name3'],
  '',
]

/** True when a layer's text comes from a feature's name (not a road ref or house number). */
export function usesNameField(textField: unknown): boolean {
  return JSON.stringify(textField ?? '').includes('"name')
}
