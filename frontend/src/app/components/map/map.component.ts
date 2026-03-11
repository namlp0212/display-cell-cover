import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import * as L from 'leaflet';
import '@maplibre/maplibre-gl-leaflet';
import { RasterTileSource, VectorTileSource } from 'maplibre-gl';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, switchMap } from 'rxjs/operators';
import { LayerService } from '../../services/layer.service';
import { RasterCoverage } from '../../models/raster-coverage.model';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-map',
  standalone: true,
  templateUrl: './map.component.html',
  styleUrl: './map.component.scss'
})
export class MapComponent implements OnInit, OnDestroy {
  @Output() coveragesChanged = new EventEmitter<RasterCoverage[]>();

  // Zoom boundaries
  private static readonly ZOOM_WMS = 12;  // < 12: hex heatmap  |  >= 12: WMS raster

  private map!: L.Map;
  private glLayer!: L.MaplibreGL;
  private hiddenCellIds: string[] = [];
  private currentCqlFilter: string | null = null;
  private moveEnd$ = new Subject<L.LatLngBounds>();
  private subscription!: Subscription;

  constructor(private layerService: LayerService) {}

  ngOnInit(): void {
    this.initMap();
    this.initMoveEndSubscription();
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.map?.remove();
  }

  /** Goi tu AppComponent sau khi toggle visibility */
  refreshMap(): void {
    this.layerService.getHiddenCellIds().subscribe({
      next: (ids) => {
        this.hiddenCellIds = ids;
        this.applyToggleByZoom();
        this.moveEnd$.next(this.map.getBounds());
      }
    });
  }

  // --- Color helpers ---------------------------------------------------------

  /** Fill color by count (0 = off-cell). */
  static getColor(count: number): string {
    if (count <= 0)  return '#FF0000';  // OFF
    if (count < 5)   return '#0099FF';  // Very Weak  (1-4)
    if (count < 15)  return '#00CCFF';  // Weak       (5-14)
    if (count < 40)  return '#00FFCC';  // Medium    (15-39)
    if (count < 100) return '#00FF99';  // Strong    (40-99)
    return                  '#00FF00';  // Very Strong (>=100)
  }

  /** Darken a #RRGGBB hex color by 20%. */
  private static darken(hex: string): string {
    const r = Math.round(parseInt(hex.slice(1, 3), 16) * 0.8);
    const g = Math.round(parseInt(hex.slice(3, 5), 16) * 0.8);
    const b = Math.round(parseInt(hex.slice(5, 7), 16) * 0.8);
    return '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('');
  }

  // --- Map init --------------------------------------------------------------

  private initMap(): void {
    this.map = L.map('map', {
      center: [21.028, 105.854],
      zoom: 12,
      maxZoom: 20,
      zoomControl: false
    });

    L.control.zoom({ position: 'topright' }).addTo(this.map);
    L.control.scale({ position: 'bottomleft' }).addTo(this.map);

    this.glLayer = L.maplibreGL({
      style: {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
          'google': {
            type: 'raster',
            tiles: ['https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}'],
            tileSize: 256,
            attribution: '© Google Maps'
          },
          'hex-coverage': {
            type: 'vector',
            tiles: [`${environment.pgTileservUrl}/public.hex_density_tile/{z}/{x}/{y}.pbf`],
            minzoom: 0,
            maxzoom: 11
          },
          // Single composite source: backend merges on-cell (normal) and off-cell
          // (red) tiles pixel-by-pixel so pixels covered by any on-cell always
          // show the normal coverage colour, never red.
          'wms-composite': {
            type: 'raster',
            tiles: [this.buildWmsCompositeUrl('')],
            tileSize: 256,
            maxzoom: 13
          }
        },
        layers: [
          { id: 'google-tiles', type: 'raster', source: 'google' },
          {
            id: 'hex-fill',
            type: 'fill',
            source: 'hex-coverage',
            'source-layer': 'hex_coverage',
            maxzoom: MapComponent.ZOOM_WMS,
            paint: {
              'fill-color': [
                'case',
                ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], '#FF0000',
                ['>=', ['get', 'count'], 100], '#00FF00',
                ['>=', ['get', 'count'], 40],  '#00FF99',
                ['>=', ['get', 'count'], 15],  '#00FFCC',
                ['>=', ['get', 'count'], 5],   '#00CCFF',
                ['>', ['get', 'count'], 0],    '#0099FF',
                'transparent'
              ],
              'fill-opacity': [
                'case',
                ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], 0.75,
                ['>=', ['get', 'count'], 100], 0.85,
                ['>=', ['get', 'count'], 40],  0.75,
                ['>=', ['get', 'count'], 15],  0.65,
                ['>=', ['get', 'count'], 5],   0.55,
                ['>', ['get', 'count'], 0],    0.45,
                0
              ],
              'fill-outline-color': [
                'case',
                ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], '#CC0000',
                ['>=', ['get', 'count'], 100], '#00CC00',
                ['>=', ['get', 'count'], 40],  '#00CC7A',
                ['>=', ['get', 'count'], 15],  '#00CCA3',
                ['>=', ['get', 'count'], 5],   '#00A3CC',
                ['>', ['get', 'count'], 0],    '#007ACC',
                'transparent'
              ]
            }
          },
          {
            id: 'hex-stroke',
            type: 'line',
            source: 'hex-coverage',
            'source-layer': 'hex_coverage',
            maxzoom: MapComponent.ZOOM_WMS,
            paint: {
              'line-color': [
                'case',
                ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], '#CC0000',
                ['>=', ['get', 'count'], 100], '#00CC00',
                ['>=', ['get', 'count'], 40],  '#00CC7A',
                ['>=', ['get', 'count'], 15],  '#00CCA3',
                ['>=', ['get', 'count'], 5],   '#00A3CC',
                ['>', ['get', 'count'], 0],    '#007ACC',
                'transparent'
              ],
              'line-width': 0.5,
              'line-opacity': 1.0
            }
          },
          {
            // Composite tile: backend already applies pixel-accurate logic
            // (on-cell pixels take priority over off-cell red pixels).
            id: 'wms-composite-raster',
            type: 'raster',
            source: 'wms-composite',
            minzoom: MapComponent.ZOOM_WMS,
            paint: {
              'raster-opacity': 0.65,
              'raster-fade-duration': 200
            }
          }
        ]
      }
    }).addTo(this.map);

    this.glLayer.getMaplibreMap().on('load', () => {
      this.setupPopup();
      this.setupZoomBadge();
      this.applyToggleByZoom();
    });

    this.addLegend();

    this.map.on('moveend', () => {
      this.moveEnd$.next(this.map.getBounds());
    });
  }

  // --- Popup -----------------------------------------------------------------

  private setupPopup(): void {
    const glMap = this.glLayer.getMaplibreMap();
    const popup = L.popup({ maxWidth: 300 });

    this.map.on('click', (e: L.LeafletMouseEvent) => {
      const point = glMap.project([e.latlng.lng, e.latlng.lat]);
      const features = glMap.queryRenderedFeatures(point, { layers: ['hex-fill'] });
      if (!features?.length) return;
      const p = features[0].properties;
      popup
        .setLatLng(e.latlng)
        .setContent(`
          <strong>Coverage density</strong><br>
          Visible cells: <b>${p?.['count']}</b><br>
          Total cells: <b>${p?.['total']}</b>
        `)
        .openOn(this.map);
    });
  }

  // --- Zoom badge ------------------------------------------------------------

  private setupZoomBadge(): void {
    this.map.on('zoom', () => {
      const z = this.map.getZoom();
      const badge = document.getElementById('zoom-badge');
      if (!badge) return;
      if (z < MapComponent.ZOOM_WMS) {
        badge.textContent = `Z${z.toFixed(1)} — Hex heatmap`;
        badge.className = 'zoom-badge hex';
      } else {
        badge.textContent = `Z${z.toFixed(1)} — WMS raster`;
        badge.className = 'zoom-badge wms';
      }
    });
  }

  // --- Move end -> fetch coverages -------------------------------------------

  private initMoveEndSubscription(): void {
    this.subscription = this.moveEnd$.pipe(
      debounceTime(150),
      switchMap(bounds => {
        const sw = bounds.getSouthWest();
        const ne = bounds.getNorthEast();
        return this.layerService.getVisibleLayers(sw.lng, sw.lat, ne.lng, ne.lat);
      })
    ).subscribe({
      next: (coverages) => {
        this.updateHiddenList(coverages);
        this.coveragesChanged.emit(coverages);
      },
      error: (err) => console.error('Error fetching layers:', err)
    });

    setTimeout(() => this.refreshMap(), 100);
  }

  private updateHiddenList(coverages: RasterCoverage[]): void {
    const inViewportIds = new Set(coverages.map(c => c.cellId));
    const inViewportHidden = coverages.filter(c => !c.visible).map(c => c.cellId);
    const outOfViewportHidden = this.hiddenCellIds.filter(id => !inViewportIds.has(id));
    this.hiddenCellIds = [...new Set([...inViewportHidden, ...outOfViewportHidden])];
    this.applyToggleByZoom();
  }

  // --- Toggle logic by zoom --------------------------------------------------

  private applyToggleByZoom(): void {
    const glMap = this.glLayer?.getMaplibreMap();
    if (!glMap?.loaded()) return;
    if (this.map.getZoom() >= MapComponent.ZOOM_WMS) {
      this.updateWmsSource();
    } else {
      this.reloadHexTiles();
    }
  }

  private reloadHexTiles(): void {
    const glMap = this.glLayer.getMaplibreMap();
    const src = glMap.getSource('hex-coverage') as VectorTileSource;
    src?.setTiles([
      `${environment.pgTileservUrl}/public.hex_density_tile/{z}/{x}/{y}.pbf?t=${Date.now()}`
    ]);
  }

  private updateWmsSource(): void {
    const hiddenKey = [...this.hiddenCellIds].sort().join(',');
    if (hiddenKey === this.currentCqlFilter) return;
    this.currentCqlFilter = hiddenKey;

    const glMap = this.glLayer.getMaplibreMap();
    const src = glMap.getSource('wms-composite') as RasterTileSource;
    src?.setTiles([this.buildWmsCompositeUrl(hiddenKey)]);
  }

  private buildWmsCompositeUrl(hiddenIds: string): string {
    const base = `${environment.apiUrl}/wms-tile-composite?bbox={bbox-epsg-3857}`;
    return hiddenIds ? `${base}&hidden=${encodeURIComponent(hiddenIds)}` : base;
  }

  // --- Legend ----------------------------------------------------------------

  private addLegend(): void {
    const grades: { label: string; color: string; dashed?: boolean }[] = [
      { label: 'Very Strong (>=100)', color: 'rgba(  0,255,  0, 0.85)' },
      { label: 'Strong    (40-99)',   color: 'rgba(  0,255,153, 0.75)' },
      { label: 'Medium    (15-39)',   color: 'rgba(  0,255,204, 0.65)' },
      { label: 'Weak       (5-14)',   color: 'rgba(  0,204,255, 0.55)' },
      { label: 'Very Weak   (1-4)',   color: 'rgba(  0,153,255, 0.45)' },
      { label: 'OFF (cell off)',      color: 'rgba(255,  0,  0, 0.75)', dashed: true },
    ];

    const LegendControl = L.Control.extend({
      onAdd(): HTMLElement {
        const div = L.DomUtil.create('div', 'hex-legend');
        div.innerHTML = `
          <div class="hex-legend__title">Signal Density</div>
          ${grades.map(g => {
            const extra = g.dashed
              ? 'outline: 1.5px dashed #CC0000; outline-offset: -2px;'
              : '';
            return `
            <div class="hex-legend__item">
              <span class="hex-legend__swatch" style="background:${g.color};${extra}"></span>
              <span class="hex-legend__label">${g.label}</span>
            </div>`;
          }).join('')}
        `;
        L.DomEvent.disableClickPropagation(div);
        L.DomEvent.disableScrollPropagation(div);
        return div;
      }
    });

    new LegendControl({ position: 'bottomright' }).addTo(this.map);
  }
}
