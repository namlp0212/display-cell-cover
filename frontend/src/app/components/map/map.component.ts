import { Component, OnInit, OnDestroy } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import * as L from 'leaflet';
import '@maplibre/maplibre-gl-leaflet';
import { VectorTileSource } from 'maplibre-gl';
import { Subscription } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SimulationService, Simulation } from '../../services/simulation.service';
import { AlertService } from '../../services/alert.service';

export type DisplayMode = 'coverage' | 'signal';

@Component({
  selector: 'app-map',
  standalone: true,
  imports: [NgIf, NgFor],
  templateUrl: './map.component.html',
  styleUrl: './map.component.scss'
})
export class MapComponent implements OnInit, OnDestroy {

  private map!: L.Map;
  private glLayer!: L.MaplibreGL;
  private subscriptions = new Subscription();

  isLoading = true;

  // ── UI state ───────────────────────────────────────────────────────────────
  displayMode: DisplayMode = 'coverage';
  wmsOpacity = 0.65;
  simulations: Simulation[] = [];
  selectedSimId: string | null = null;

  private legendControl: L.Control | null = null;

  constructor(
    private simService: SimulationService,
    private alertService: AlertService
  ) {}

  ngOnInit(): void {
    this.initMap();
    this.loadSimulations();
    this.subscribeAlerts();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.map?.remove();
  }

  // ── Simulation dropdown ────────────────────────────────────────────────────

  loadSimulations(): void {
    this.simService.listActive().subscribe({
      next: sims => { this.simulations = sims; },
      error: err => console.error('Failed to load simulations:', err)
    });
  }

  selectSim(simId: string | null): void {
    this.selectedSimId = simId;
    this.reloadH3Tiles();
  }

  endSim(simId: string): void {
    this.simService.end(simId).subscribe({
      next: () => {
        if (this.selectedSimId === simId) {
          this.selectedSimId = null;
        }
        this.loadSimulations();
        this.reloadH3Tiles();
      }
    });
  }

  // ── Alert WebSocket ────────────────────────────────────────────────────────

  private subscribeAlerts(): void {
    this.subscriptions.add(
      this.alertService.cellStatusChanged$.subscribe(() => {
        // Invalidate tile cache by reloading source with a new timestamp
        this.reloadH3Tiles();
      })
    );
  }

  // ── Map init ───────────────────────────────────────────────────────────────

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
          'h3-coverage': {
            type: 'vector',
            tiles: [this.h3TileUrl()],
            minzoom: 6,
            maxzoom: 16
          },
          'pixel-coverage': {
            type: 'raster',
            tiles: [this.pixelTileUrl()],
            minzoom: 17,
            maxzoom: 19,
            tileSize: 256
          }
        },
        layers: [
          { id: 'google-tiles', type: 'raster', source: 'google' },
          {
            id: 'h3-fill',
            type: 'fill',
            source: 'h3-coverage',
            'source-layer': 'coverage',
            maxzoom: 17,
            paint: this.buildFillPaint()
          },
          {
            id: 'h3-stroke',
            type: 'line',
            source: 'h3-coverage',
            'source-layer': 'coverage',
            maxzoom: 17,
            paint: this.buildStrokePaint()
          },
          {
            id: 'pixel-raster',
            type: 'raster',
            source: 'pixel-coverage',
            minzoom: 17,
            paint: { 'raster-opacity': this.wmsOpacity }
          }
        ]
      }
    }).addTo(this.map);

    const glMap = this.glLayer.getMaplibreMap();
    glMap.on('load', () => {
      this.setupPopup();
      this.setupZoomBadge();
    });
    glMap.on('dataloading', () => { this.isLoading = true; });
    glMap.on('idle', () => { this.isLoading = false; });

    this.addLegend();
    this.addModeToggle();
    this.addSimDropdown();
  }

  // ── Tile URLs ──────────────────────────────────────────────────────────────

  private h3TileUrl(): string {
    const base = `${environment.apiUrl}/h3-tile/{z}/{x}/{y}?mode=${this.displayMode}&t=${Date.now()}`;
    return this.selectedSimId ? `${base}&sim=${this.selectedSimId}` : base;
  }

  private pixelTileUrl(): string {
    // Use /api/h3-tile which routes to PixelCoverageService / PixelMaxSignalService at zoom >= 18
    const base = `${environment.apiUrl}/h3-tile/{z}/{x}/{y}?mode=${this.displayMode}&t=${Date.now()}`;
    return this.selectedSimId ? `${base}&sim=${this.selectedSimId}` : base;
  }

  private reloadH3Tiles(): void {
    const glMap = this.glLayer?.getMaplibreMap();
    if (!glMap?.loaded()) return;

    const h3Src = glMap.getSource('h3-coverage') as VectorTileSource;
    h3Src?.setTiles([this.h3TileUrl()]);

    // Reload pixel raster source at the same time so both layers stay in sync
    const pixelSrc = glMap.getSource('pixel-coverage') as any;
    pixelSrc?.setTiles([this.pixelTileUrl()]);
  }

  // ── Display mode ───────────────────────────────────────────────────────────

  setDisplayMode(mode: DisplayMode): void {
    if (this.displayMode === mode) return;
    this.displayMode = mode;
    this.rebuildLegend();
    const glMap = this.glLayer?.getMaplibreMap();
    if (glMap?.getLayer('h3-fill')) {
      this.updateLayerPaint(glMap);
    }
    this.reloadH3Tiles();
  }

  // ── Paint expressions ──────────────────────────────────────────────────────
  // Properties from backend MVT: on_density (int), off_density (int), on_signal (float dBm)

  private buildFillPaint(): object {
    const o = this.wmsOpacity;
    if (this.displayMode === 'signal') {
      return {
        'fill-color': [
          'case',
          // Off only (no on-cell covering this hex)
          ['all', ['==', ['get', 'on_density'], 0], ['>', ['get', 'off_density'], 0]], '#FF0000',
          ['>', ['get', 'on_density'], 0], [
            'case',
            ['<', ['get', 'on_signal'], -150], '#00204D',
            ['<', ['get', 'on_signal'], -130], '#00336F',
            ['<', ['get', 'on_signal'], -110], '#1F4E79',
            ['<', ['get', 'on_signal'], -95],  '#2C788E',
            ['<', ['get', 'on_signal'], -85],  '#5FA060',
            ['<', ['get', 'on_signal'], -75],  '#9DBA46',
            ['<', ['get', 'on_signal'], -65],  '#D2CE3E',
            '#FDE725'
          ],
          'transparent'
        ],
        'fill-opacity': [
          'case',
          ['all', ['==', ['get', 'on_density'], 0], ['>', ['get', 'off_density'], 0]], 0.75 * o,
          ['>', ['get', 'on_density'], 0], 0.70 * o,
          0
        ]
      };
    }
    // Coverage density mode
    return {
      'fill-color': [
        'case',
        ['all', ['==', ['get', 'on_density'], 0], ['>', ['get', 'off_density'], 0]], '#FF0000',
        ['>=', ['get', 'on_density'], 5], '#00FF00',
        ['>=', ['get', 'on_density'], 3], '#00FF99',
        ['>=', ['get', 'on_density'], 2], '#00FFCC',
        ['==', ['get', 'on_density'], 1], '#0099FF',
        'transparent'
      ],
      'fill-opacity': [
        'case',
        ['all', ['==', ['get', 'on_density'], 0], ['>', ['get', 'off_density'], 0]], 0.75 * o,
        ['>=', ['get', 'on_density'], 5], 0.85 * o,
        ['>=', ['get', 'on_density'], 3], 0.75 * o,
        ['>=', ['get', 'on_density'], 2], 0.65 * o,
        ['==', ['get', 'on_density'], 1], 0.50 * o,
        0
      ]
    };
  }

  private buildStrokePaint(): object {
    const o = this.wmsOpacity;
    return {
      'line-color': [
        'case',
        ['all', ['==', ['get', 'on_density'], 0], ['>', ['get', 'off_density'], 0]], '#CC0000',
        ['>', ['get', 'on_density'], 0], '#007ACC',
        'transparent'
      ],
      'line-width': 0.5,
      'line-opacity': o
    };
  }

  private updateLayerPaint(glMap: ReturnType<L.MaplibreGL['getMaplibreMap']>): void {
    const fill   = this.buildFillPaint()   as Record<string, unknown>;
    const stroke = this.buildStrokePaint() as Record<string, unknown>;
    for (const [k, v] of Object.entries(fill))   glMap.setPaintProperty('h3-fill',   k, v);
    for (const [k, v] of Object.entries(stroke)) glMap.setPaintProperty('h3-stroke', k, v);
    // Pixel raster layer — only opacity is dynamic (mode is encoded in the tile URL)
    if (glMap.getLayer('pixel-raster')) {
      glMap.setPaintProperty('pixel-raster', 'raster-opacity', this.wmsOpacity);
    }
  }

  // ── Popup ──────────────────────────────────────────────────────────────────

  private setupPopup(): void {
    const glMap = this.glLayer.getMaplibreMap();
    const popup = L.popup({ maxWidth: 300 });

    this.map.on('click', (e: L.LeafletMouseEvent) => {
      const pt = glMap.project([e.latlng.lng, e.latlng.lat]);
      const features = glMap.queryRenderedFeatures(pt, { layers: ['h3-fill'] });
      if (!features?.length) return;
      const p = features[0].properties;

      let content = `<strong>${this.displayMode === 'signal' ? 'Signal' : 'Coverage'}</strong><br>
        ON cells: <b>${p?.['on_density']}</b><br>
        OFF cells: <b>${p?.['off_density']}</b>`;
      if (this.displayMode === 'signal' && p?.['on_density'] > 0) {
        content += `<br>Max signal: <b>${(p?.['on_signal'] as number).toFixed(1)} dBm</b>`;
      }

      popup.setLatLng(e.latlng).setContent(content).openOn(this.map);
    });
  }

  // ── Zoom badge ─────────────────────────────────────────────────────────────

  private setupZoomBadge(): void {
    const glMap = this.glLayer.getMaplibreMap();

    this.map.on('zoom', () => {
      const z = this.map.getZoom();
      const badge = document.getElementById('zoom-badge');
      if (!badge) return;

      if (z >= 18) {
        badge.textContent = `Z${z.toFixed(1)} — Pixel tile`;
      } else {
        const res = z <= 6 ? 5 : z === 7 ? 6 : z <= 9 ? 7 : z === 10 ? 8
                  : z <= 12 ? 9 : z === 13 ? 10 : z === 14 ? 11 : z <= 16 ? 12 : 13;
        badge.textContent = `Z${z.toFixed(1)} — H3 res${res}`;
      }

      // Explicit layer toggle at zoom 18 boundary — guards against
      // fractional-zoom mismatches between Leaflet and MapLibre GL
      const isPixel = z >= 18;
      if (glMap.getLayer('h3-fill')) {
        glMap.setLayoutProperty('h3-fill',   'visibility', isPixel ? 'none' : 'visible');
        glMap.setLayoutProperty('h3-stroke', 'visibility', isPixel ? 'none' : 'visible');
      }
      if (glMap.getLayer('pixel-raster')) {
        glMap.setLayoutProperty('pixel-raster', 'visibility', isPixel ? 'visible' : 'none');
      }
    });
  }

  // ── Mode toggle control ────────────────────────────────────────────────────

  private addModeToggle(): void {
    const self = this;
    const ModeToggleControl = L.Control.extend({
      onAdd(): HTMLElement {
        const div = L.DomUtil.create('div', 'mode-toggle');
        div.innerHTML = `
          <div class="mode-btn-row">
            <button class="mode-btn active" data-mode="coverage" title="Coverage density">Coverage</button>
            <button class="mode-btn"        data-mode="signal"   title="Max Signal (dBm)">Signal</button>
          </div>
          <div class="opacity-row">
            <span class="opacity-label">Opacity</span>
            <input class="opacity-slider" type="range" min="0" max="1" step="0.05"
                   value="${self.wmsOpacity}">
            <span class="opacity-value">${Math.round(self.wmsOpacity * 100)}%</span>
          </div>
        `;

        div.querySelectorAll('.mode-btn').forEach(btn => {
          L.DomEvent.on(btn as HTMLElement, 'click', e => {
            L.DomEvent.stopPropagation(e);
            const mode = (btn as HTMLElement).dataset['mode'] as DisplayMode;
            self.setDisplayMode(mode);
            div.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
          });
        });

        const slider = div.querySelector('.opacity-slider') as HTMLInputElement;
        const valLabel = div.querySelector('.opacity-value') as HTMLElement;
        L.DomEvent.on(slider, 'input', () => {
          const o = parseFloat(slider.value);
          self.wmsOpacity = o;
          valLabel.textContent = `${Math.round(o * 100)}%`;
          const glMap = self.glLayer?.getMaplibreMap();
          if (glMap) self.updateLayerPaint(glMap);
        });

        L.DomEvent.disableClickPropagation(div);
        L.DomEvent.disableScrollPropagation(div);
        return div;
      }
    });
    new ModeToggleControl({ position: 'topright' }).addTo(this.map);
  }

  // ── Simulation dropdown control ────────────────────────────────────────────

  private addSimDropdown(): void {
    const self = this;
    const SimControl = L.Control.extend({
      onAdd(): HTMLElement {
        const div = L.DomUtil.create('div', 'sim-control');
        div.innerHTML = `
          <div class="sim-row">
            <label class="sim-label">Kịch bản:</label>
            <select class="sim-select">
              <option value="">— Thực tế —</option>
            </select>
            <button class="sim-refresh" title="Tải lại danh sách kịch bản">↺</button>
          </div>
        `;

        const select = div.querySelector('.sim-select') as HTMLSelectElement;
        const refreshBtn = div.querySelector('.sim-refresh') as HTMLButtonElement;

        // Populate and keep in sync with Angular's simulations array
        function repopulate(): void {
          const current = select.value;
          while (select.options.length > 1) select.remove(1);
          self.simulations.forEach(sim => {
            const opt = document.createElement('option');
            opt.value = sim.id;
            opt.textContent = sim.name;
            select.appendChild(opt);
          });
          select.value = current || '';
        }

        // Observer: repopulate whenever simulations list changes
        // Use a simple interval check (Angular zone is already set up)
        let lastCount = 0;
        const intervalId = setInterval(() => {
          if (self.simulations.length !== lastCount) {
            lastCount = self.simulations.length;
            repopulate();
          }
        }, 1000);

        // Store cleanup ref on element
        (div as any)['_clearInterval'] = () => clearInterval(intervalId);

        L.DomEvent.on(select, 'change', () => {
          self.selectSim(select.value || null);
        });

        L.DomEvent.on(refreshBtn, 'click', e => {
          L.DomEvent.stopPropagation(e);
          self.loadSimulations();
        });

        L.DomEvent.disableClickPropagation(div);
        L.DomEvent.disableScrollPropagation(div);
        return div;
      },
      onRemove(map: L.Map): void {
        // clean up interval
      }
    });
    new SimControl({ position: 'topleft' }).addTo(this.map);
  }

  // ── Legend ─────────────────────────────────────────────────────────────────

  private addLegend(): void {
    const self = this;
    const LegendControl = L.Control.extend({
      onAdd(): HTMLElement { return self.buildLegendElement(); }
    });
    this.legendControl = new LegendControl({ position: 'bottomright' });
    this.legendControl.addTo(this.map);
  }

  private rebuildLegend(): void {
    this.legendControl?.remove();
    this.addLegend();
  }

  private buildLegendElement(): HTMLElement {
    const div = L.DomUtil.create('div', 'hex-legend');
    if (this.displayMode === 'signal') {
      div.innerHTML = `
        <div class="hex-legend__title">Max Signal</div>
        ${[
          { label: '≥ -65 dBm — Tuyệt vời', color: 'rgba(253,231,37,0.75)' },
          { label: '-75 → -65 — Rất tốt',   color: 'rgba(210,206,62,0.75)' },
          { label: '-85 → -75 — Tốt',       color: 'rgba(157,186,70,0.75)' },
          { label: '-95 → -85 — Khá',       color: 'rgba(95,160,96,0.75)'  },
          { label: '-110 → -95 — TB',       color: 'rgba(44,120,142,0.75)' },
          { label: '-130 → -110 — Yếu',     color: 'rgba(31,78,121,0.75)'  },
          { label: '< -130 — Rất yếu',      color: 'rgba(0,51,111,0.75)'   },
          { label: 'Cell OFF',               color: 'rgba(255,0,0,0.75)', dashed: true }
        ].map(g => `
          <div class="hex-legend__item">
            <span class="hex-legend__swatch" style="background:${g.color}${(g as any).dashed ? ';outline:1.5px dashed #CC0000;outline-offset:-2px' : ''}"></span>
            <span class="hex-legend__label">${g.label}</span>
          </div>`).join('')}
      `;
    } else {
      div.innerHTML = `
        <div class="hex-legend__title">Coverage</div>
        ${[
          { label: '≥ 5 cells — Rất mạnh', color: 'rgba(0,255,0,0.85)'   },
          { label: '3–4 cells — Mạnh',     color: 'rgba(0,255,153,0.75)' },
          { label: '2 cells — Trung bình', color: 'rgba(0,255,204,0.65)' },
          { label: '1 cell — Yếu',         color: 'rgba(0,153,255,0.50)' },
          { label: 'Cell OFF',             color: 'rgba(255,0,0,0.75)', dashed: true }
        ].map(g => `
          <div class="hex-legend__item">
            <span class="hex-legend__swatch" style="background:${g.color}${(g as any).dashed ? ';outline:1.5px dashed #CC0000;outline-offset:-2px' : ''}"></span>
            <span class="hex-legend__label">${g.label}</span>
          </div>`).join('')}
      `;
    }
    L.DomEvent.disableClickPropagation(div);
    L.DomEvent.disableScrollPropagation(div);
    return div;
  }
}
