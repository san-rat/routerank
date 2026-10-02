import { layers, namedFlavor, type Flavor } from '@protomaps/basemaps'
import type { LayerSpecification, StyleSpecification } from 'maplibre-gl'
import { englishLabel, usesNameField } from './labels'

const ASSETS = 'https://protomaps.github.io/basemaps-assets'

export const ATTRIBUTION =
  '<a href="https://protomaps.com">Protomaps</a> · ' +
  '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'

// Protomaps' light flavour, recoloured toward the Figma palette. Roads stay
// neutral white and grey: teal is reserved for the heatmap, red for bus routes.
const flavor: Flavor = {
  ...namedFlavor('light'),
  background: '#efeeea',
  earth: '#efeeea',
  water: '#bcdcec',
  park_a: '#dfe8dc',
  park_b: '#cfe2cc',
  wood_a: '#e1e8dc',
  wood_b: '#d2e2cc',
  scrub_a: '#e3e8de',
  scrub_b: '#d6e2d2',
  ocean_label: '#6f8fa8',
  city_label: '#5a6466',
  city_label_halo: '#efeeea',
  subplace_label: '#929a9c',
  subplace_label_halo: '#efeeea',
  state_label: '#929a9c',
  state_label_halo: '#efeeea',
  country_label: '#929a9c',
}

/** Replaces every name-based label with the English-or-hidden rule. */
function withEnglishLabels(layer: LayerSpecification): LayerSpecification {
  if (layer.type !== 'symbol' || !usesNameField(layer.layout?.['text-field'])) return layer
  return { ...layer, layout: { ...layer.layout, 'text-field': englishLabel } }
}

export function mapStyle(tilesUrl: string): StyleSpecification {
  return {
    version: 8,
    glyphs: `${ASSETS}/fonts/{fontstack}/{range}.pbf`,
    sprite: `${ASSETS}/sprites/v4/light`,
    sources: {
      protomaps: { type: 'vector', url: `pmtiles://${tilesUrl}`, attribution: ATTRIBUTION },
    },
    layers: layers('protomaps', flavor, { lang: 'en' }).map(withEnglishLabels),
  }
}
