import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { NgIf } from '@angular/common';
import * as L from 'leaflet';
import '@maplibre/maplibre-gl-leaflet';
import { RasterTileSource, VectorTileSource } from 'maplibre-gl';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, switchMap } from 'rxjs/operators';
import { LayerService } from '../../services/layer.service';
import { RasterCoverage } from '../../models/raster-coverage.model';
import { environment } from '../../../environments/environment';

export type DisplayMode = 'coverage' | 'signal-max';

@Component({
  selector: 'app-map',
  standalone: true,
  imports: [NgIf],
  templateUrl: './map.component.html',
  styleUrl: './map.component.scss'
})
export class MapComponent implements OnInit, OnDestroy {
  @Output() coveragesChanged = new EventEmitter<RasterCoverage[]>();

  // Zoom boundaries
  private static readonly ZOOM_WMS = 13;  // < 13: hex heatmap  |  >= 13: WMS raster

  private map!: L.Map;
  private glLayer!: L.MaplibreGL;
  private tileVersion = 0;
  private moveEnd$ = new Subject<L.LatLngBounds>();
  private subscription!: Subscription;

  isLoading = true;

  /** Current display mode */
  displayMode: DisplayMode = 'coverage';

  /** Reference to the legend control so we can remove/re-add it on mode change */
  private legendControl: L.Control | null = null;

  constructor(private layerService: LayerService) {}

  ngOnInit(): void {
    this.initMap();
    this.initMoveEndSubscription();
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.map?.remove();
  }

  /** Called from AppComponent after toggle visibility */
  refreshMap(): void {
    this.tileVersion++;
    this.applyToggleByZoom();
    this.moveEnd$.next(this.map.getBounds());
  }

  /** Switch between coverage / signal-avg / signal-max modes */
  setDisplayMode(mode: DisplayMode): void {
    if (this.displayMode === mode) return;
    this.displayMode = mode;
    this.applyToggleByZoom();
    this.rebuildLegend();
    const glMap = this.glLayer?.getMaplibreMap();
    if (glMap?.getLayer('hex-fill')) {
      this.updateHexLayerPaint(glMap);
    }
  }

  // --- Color helpers ---------------------------------------------------------

  /** Fill color by count (0 = off-cell). Used in coverage mode. */
  static getColor(count: number): string {
    if (count <= 0)  return '#FF0000';  // OFF
    if (count < 5)   return '#0099FF';  // Very Weak  (1-4)
    if (count < 15)  return '#00CCFF';  // Weak       (5-14)
    if (count < 40)  return '#00FFCC';  // Medium    (15-39)
    if (count < 100) return '#00FF99';  // Strong    (40-99)
    return                  '#00FF00';  // Very Strong (>=100)
  }

  /**
   * Fill color by signal strength (dBm scale, range -140 to -73).
   * Used in both signal-avg and signal-max modes.
   */
  static getSignalColor(dbm: number): string {
    if (dbm < -130) return '#00204D';   // Rất kém
    if (dbm < -120) return '#00336F';   // Kém
    if (dbm < -110) return '#1F4E79';   // Yếu
    if (dbm < -100) return '#2C788E';   // Trung bình
    if (dbm < -90)  return '#5FA060';   // Khá
    if (dbm < -85)  return '#9DBA46';   // Tốt
    if (dbm < -80)  return '#D2CE3E';   // Rất tốt
    return                 '#FDE725';   // Tuyệt vời
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
      center: [16.054, 108.202],
      zoom: 12,
      maxZoom: 20,
      zoomControl: false,
      zoomSnap: 0.5,
      zoomDelta: 0.5,
      wheelPxPerZoomLevel: 120
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
            maxzoom: 12
          },
          // Single composite source: backend merges on-cell (normal) and off-cell
          // (red) tiles pixel-by-pixel so pixels covered by any on-cell always
          // show the normal coverage colour, never red.
          'wms-composite': {
            type: 'raster',
            tiles: [this.baseTileUrl()],
            tileSize: 256,
            maxzoom: 18
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
            paint: this.buildHexFillPaint()
          },
          {
            id: 'hex-stroke',
            type: 'line',
            source: 'hex-coverage',
            'source-layer': 'hex_coverage',
            maxzoom: MapComponent.ZOOM_WMS,
            paint: this.buildHexStrokePaint()
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
              'raster-fade-duration': 0
            }
          }
        ]
      }
    }).addTo(this.map);

    const glMap = this.glLayer.getMaplibreMap();

    glMap.on('load', () => {
      this.setupPopup();
      this.setupZoomBadge();
      this.applyToggleByZoom();
    });

    glMap.on('dataloading', () => { this.isLoading = true; });
    glMap.on('idle', () => { this.isLoading = false; });

    this.addLegend();
    this.addModeToggle();

    this.map.on('moveend', () => {
      this.moveEnd$.next(this.map.getBounds());
    });
  }

  // --- Hex layer paint expressions -------------------------------------------

  /** MapLibre expression for signal color ramp (dBm scale, -140 to -73). */
  private signalColorExpr(prop: 'avg_signal' | 'max_signal'): unknown[] {
    return [
      'case',
      ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], '#FF0000',
      ['!', ['has', prop]], '#1F4E79',
      ['<', ['get', prop], -130], '#00204D',
      ['<', ['get', prop], -120], '#00336F',
      ['<', ['get', prop], -110], '#1F4E79',
      ['<', ['get', prop], -100], '#2C788E',
      ['<', ['get', prop], -90],  '#5FA060',
      ['<', ['get', prop], -85],  '#9DBA46',
      ['<', ['get', prop], -80],  '#D2CE3E',
      '#FDE725'
    ];
  }

  private buildHexFillPaint(): object {
    if (this.displayMode === 'signal-max') {
      return {
        'fill-color': this.signalColorExpr('max_signal'),
        'fill-opacity': [
          'case',
          ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], 0.75,
          ['>', ['get', 'count'], 0], 0.70,
          0
        ],
        'fill-outline-color': [
          'case',
          ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], '#CC0000',
          ['>', ['get', 'count'], 0], '#2C788E',
          'transparent'
        ]
      };
    }
    // Coverage mode (default)
    return {
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
    };
  }

  private buildHexStrokePaint(): object {
    if (this.displayMode === 'signal-max') {
      return {
        'line-color': [
          'case',
          ['all', ['==', ['get', 'count'], 0], ['>', ['get', 'total'], 0]], '#CC0000',
          ['>', ['get', 'count'], 0], '#007A99',
          'transparent'
        ],
        'line-width': 0.5,
        'line-opacity': 1.0
      };
    }
    return {
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
    };
  }

  /** Re-applies hex fill/stroke paint expressions to the loaded MapLibre map. */
  private updateHexLayerPaint(glMap: ReturnType<L.MaplibreGL['getMaplibreMap']>): void {
    const fillPaint = this.buildHexFillPaint() as Record<string, unknown>;
    const strokePaint = this.buildHexStrokePaint() as Record<string, unknown>;

    // fill-color, fill-opacity, fill-outline-color
    for (const [prop, val] of Object.entries(fillPaint)) {
      glMap.setPaintProperty('hex-fill', prop, val);
    }
    // line-color, line-width, line-opacity
    for (const [prop, val] of Object.entries(strokePaint)) {
      glMap.setPaintProperty('hex-stroke', prop, val);
    }
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

      const modeLabel = this.displayMode === 'signal-max' ? 'Max Signal' : 'Coverage Density';
      let content = `<strong>${modeLabel}</strong><br>
        Visible cells: <b>${p?.['count']}</b><br>
        Total cells: <b>${p?.['total']}</b>`;

      if (this.isSignalMode()) {
        const avg = p?.['avg_signal'];
        const max = p?.['max_signal'];
        if (avg != null) content += `<br>Avg signal: <b>${(avg as number).toFixed(1)} dBm</b>`;
        if (max != null) content += `<br>Max signal: <b>${(max as number).toFixed(1)} dBm</b>`;
      }

      popup
        .setLatLng(e.latlng)
        .setContent(content)
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
        const res = z <= 7 ? 'Res6' : z <= 8 ? 'Res7' : z <= 10 ? 'Res8' : 'Res9';
        badge.textContent = `Z${z.toFixed(1)} — Hex heatmap (${res})`;
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
        this.coveragesChanged.emit(coverages);
      },
      error: (err) => console.error('Error fetching layers:', err)
    });

    setTimeout(() => this.refreshMap(), 100);
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
    const glMap = this.glLayer?.getMaplibreMap();
    if (!glMap) return;
    const src = glMap.getSource('wms-composite') as RasterTileSource;
    src?.setTiles([this.baseTileUrl()]);
  }

  private baseTileUrl(): string {
    const api = environment.apiUrl;
    const v = this.tileVersion;
    if (this.displayMode === 'signal-max') return `${api}/wms-tile-signal-xyz?v=${v}&z={z}&x={x}&y={y}`;
    return `${api}/wms-tile-xyz?v=${v}&z={z}&x={x}&y={y}`;
  }

  private isSignalMode(): boolean {
    return this.displayMode === 'signal-max';
  }

  // --- Mode toggle button ----------------------------------------------------

  private addModeToggle(): void {
    const self = this;
    const ModeToggleControl = L.Control.extend({
      onAdd(): HTMLElement {
        const div = L.DomUtil.create('div', 'mode-toggle');
        div.innerHTML = `
          <button class="mode-btn active" data-mode="coverage"   title="Coverage density — số cell phủ tại mỗi hex">Coverage</button>
          <button class="mode-btn"        data-mode="signal-max" title="Max Signal — cường độ sóng tốt nhất tại mỗi điểm">Max Signal</button>
        `;

        div.querySelectorAll('.mode-btn').forEach((btn) => {
          L.DomEvent.on(btn as HTMLElement, 'click', (e) => {
            L.DomEvent.stopPropagation(e);
            const mode = (btn as HTMLElement).dataset['mode'] as DisplayMode;
            self.setDisplayMode(mode);
            div.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
          });
        });

        L.DomEvent.disableClickPropagation(div);
        L.DomEvent.disableScrollPropagation(div);
        return div;
      }
    });

    new ModeToggleControl({ position: 'topright' }).addTo(this.map);
  }

  // --- Legend ----------------------------------------------------------------

  private addLegend(): void {
    const self = this;
    const LegendControl = L.Control.extend({
      onAdd(): HTMLElement {
        return self.buildLegendElement();
      }
    });

    this.legendControl = new LegendControl({ position: 'bottomright' });
    this.legendControl.addTo(this.map);
  }

  private rebuildLegend(): void {
    if (this.legendControl) {
      this.legendControl.remove();
    }
    this.addLegend();
  }

  private buildLegendElement(): HTMLElement {
    const div = L.DomUtil.create('div', 'hex-legend');

    if (this.displayMode === 'signal-max') {
      const title = 'Max Signal';
      const grades: { label: string; color: string; dashed?: boolean }[] = [
        { label: '≥ -80 dBm — Tuyệt vời',   color: 'rgba(253,231, 37, 0.75)' },
        { label: '-85 → -80 — Rất tốt',    color: 'rgba(210,206, 62, 0.75)' },
        { label: '-90 → -85 — Tốt',        color: 'rgba(159,186, 70, 0.75)' },
        { label: '-100 → -90 — Khá',       color: 'rgba( 95,160, 96, 0.75)' },
        { label: '-110 → -100 — Trung bình',color:'rgba( 44,120,142, 0.75)' },
        { label: '-120 → -110 — Yếu',      color: 'rgba( 31, 78,121, 0.75)' },
        { label: '-130 → -120 — Rất yếu',  color: 'rgba(  0, 51,111, 0.75)' },
        { label: '< -130 — Cực yếu',       color: 'rgba(  0, 32, 77, 0.75)' },
        { label: 'Không có dữ liệu',        color: 'rgba( 31, 78,121, 0.75)' },
        { label: 'Cell tắt (OFF)',          color: 'rgba(255,  0,  0, 0.75)', dashed: true },
      ];
      div.innerHTML = `
        <div class="hex-legend__title">${title}</div>
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
    } else {
      const grades: { label: string; color: string; dashed?: boolean }[] = [
        { label: 'Very Strong (>=100)', color: 'rgba(  0,255,  0, 0.85)' },
        { label: 'Strong    (40-99)',   color: 'rgba(  0,255,153, 0.75)' },
        { label: 'Medium    (15-39)',   color: 'rgba(  0,255,204, 0.65)' },
        { label: 'Weak       (5-14)',   color: 'rgba(  0,204,255, 0.55)' },
        { label: 'Very Weak   (1-4)',   color: 'rgba(  0,153,255, 0.45)' },
        { label: 'OFF (cell off)',      color: 'rgba(255,  0,  0, 0.75)', dashed: true },
      ];
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
    }

    L.DomEvent.disableClickPropagation(div);
    L.DomEvent.disableScrollPropagation(div);
    return div;
  }
}
