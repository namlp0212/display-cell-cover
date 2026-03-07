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

  /** Gọi từ AppComponent sau khi toggle visibility */
  refreshMap(): void {
    this.layerService.getHiddenCellIds().subscribe({
      next: (ids) => {
        this.hiddenCellIds = ids;
        this.applyToggleByZoom();
        this.moveEnd$.next(this.map.getBounds());
      }
    });
  }

  // ─── Map init ──────────────────────────────────────────────────────────────

  private initMap(): void {
    this.map = L.map('map', {
      center: [21.028, 105.854],
      zoom: 12,
      maxZoom: 20,
      zoomControl: false
    });

    L.control.zoom({ position: 'topright' }).addTo(this.map);
    L.control.scale({ position: 'bottomleft' }).addTo(this.map);

    // MapLibre GL layer wrapping the full style (base + overlays)
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
          'wms-binary': {
            type: 'raster',
            tiles: [this.buildWmsUrl('')],
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
                'interpolate', ['linear'], ['get', 'count'],
                0,  'rgba(0,0,0,0)',
                1,  '#d4f1f9',
                5,  '#7ab7ce',
                15, '#2C788E',
                40, '#1e6b80',
                100,'#155e70'
              ],
              'fill-opacity': 0.55,
              'fill-outline-color': 'rgba(0,0,0,0)'
            }
          },
          {
            id: 'wms-raster',
            type: 'raster',
            source: 'wms-binary',
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

    this.map.on('moveend', () => {
      this.moveEnd$.next(this.map.getBounds());
    });
  }

  // ─── Popup (Leaflet popup triggered by MapLibre feature query) ─────────────

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

  // ─── Zoom badge (debug/UX) ─────────────────────────────────────────────────

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

  // ─── Move end → fetch coverages ───────────────────────────────────────────

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

  // ─── Toggle logic theo zoom ────────────────────────────────────────────────

  private applyToggleByZoom(): void {
    const glMap = this.glLayer?.getMaplibreMap();
    if (!glMap?.loaded()) return;
    if (this.map.getZoom() >= MapComponent.ZOOM_WMS) {
      this.updateWmsSource();
    } else {
      this.reloadHexTiles();
    }
  }

  /** Zoom 0–11: bust tile cache để server recompute hex density */
  private reloadHexTiles(): void {
    const glMap = this.glLayer.getMaplibreMap();
    const src = glMap.getSource('hex-coverage') as VectorTileSource;
    src?.setTiles([
      `${environment.pgTileservUrl}/public.hex_density_tile/{z}/{x}/{y}.pbf?t=${Date.now()}`
    ]);
  }

  /** Zoom 12+: cập nhật WMS proxy URL với hidden IDs mới */
  private updateWmsSource(): void {
    const hiddenKey = [...this.hiddenCellIds].sort().join(',');
    if (hiddenKey === this.currentCqlFilter) return;
    this.currentCqlFilter = hiddenKey;

    const glMap = this.glLayer.getMaplibreMap();
    const src = glMap.getSource('wms-binary') as RasterTileSource;
    src?.setTiles([this.buildWmsUrl(hiddenKey)]);
  }

  private buildWmsUrl(hiddenIds: string): string {
    const base = `${environment.apiUrl}/wms-tile?bbox={bbox-epsg-3857}`;
    return hiddenIds ? `${base}&hidden=${encodeURIComponent(hiddenIds)}` : base;
  }
}
