import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import maplibregl, {
  FilterSpecification,
  RasterTileSource,
  VectorTileSource
} from 'maplibre-gl';
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
  private static readonly ZOOM_HEX  = 11;  // < 11 : hex heatmap
  private static readonly ZOOM_WMS  = 13;  // >= 13: WMS raster
                                            // 11–12: cell bbox vector
  private static readonly OVERLAP_GROUPS = 8;

  private map!: maplibregl.Map;
  private hiddenCellIds: string[] = [];
  private currentCqlFilter: string | null = null;
  private moveEnd$ = new Subject<maplibregl.LngLatBounds>();
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
    this.map = new maplibregl.Map({
      container: 'map',
      style: {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
          'google': {
            type: 'raster',
            tiles: ['https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}'],
            tileSize: 256,
            attribution: '© Google Maps'
          }
        },
        layers: [
          { id: 'google-tiles', type: 'raster', source: 'google' }
        ]
      },
      center: [105.854, 21.028],
      zoom: 12,
      maxZoom: 20
    });

    this.map.addControl(new maplibregl.NavigationControl(), 'top-right');
    this.map.addControl(new maplibregl.ScaleControl(), 'bottom-left');

    this.map.on('load', () => {
      this.addSources();
      this.addLayers();
      this.setupPopup();
      this.setupZoomBadge();
    });

    this.map.on('moveend', () => {
      this.moveEnd$.next(this.map.getBounds());
    });
  }

  // ─── Sources ───────────────────────────────────────────────────────────────

  private addSources(): void {
    // Vector: hex heatmap (zoom 0–10)
    this.map.addSource('hex-coverage', {
      type: 'vector',
      tiles: [`${environment.pgTileservUrl}/public.hex_density_tile/{z}/{x}/{y}.pbf`],
      minzoom: 0,
      maxzoom: 10
    });

    // Vector: cell bbox (zoom 11–12)
    this.map.addSource('cell-bbox', {
      type: 'vector',
      tiles: [`${environment.pgTileservUrl}/public.raster_coverages/{z}/{x}/{y}.pbf`],
      minzoom: 10,
      maxzoom: 14
    });

    // Raster: WMS GeoServer (zoom 13+)
    this.map.addSource('wms-binary', {
      type: 'raster',
      tiles: [this.buildWmsUrl('')],
      tileSize: 512
    });
  }

  // ─── Layers ────────────────────────────────────────────────────────────────

  private addLayers(): void {
    const Z_HEX = MapComponent.ZOOM_HEX;
    const Z_WMS = MapComponent.ZOOM_WMS;

    // ── Zoom 0–10: Hex heatmap ──────────────────────────────────────────────
    this.map.addLayer({
      id: 'hex-fill',
      type: 'fill',
      source: 'hex-coverage',
      'source-layer': 'hex_coverage',
      maxzoom: Z_HEX,
      paint: {
        'fill-color': [
          'interpolate', ['linear'], ['get', 'count'],
          0,  'rgba(0,0,0,0)',
          1,  '#ffffb2',
          5,  '#fecc5c',
          10, '#fd8d3c',
          20, '#f03b20',
          50, '#bd0026'
        ],
        'fill-opacity': 0.75
      }
    });

    this.map.addLayer({
      id: 'hex-outline',
      type: 'line',
      source: 'hex-coverage',
      'source-layer': 'hex_coverage',
      maxzoom: Z_HEX,
      paint: {
        'line-color': 'rgba(0,0,0,0.1)',
        'line-width': 0.5
      }
    });

    // ── Zoom 11–12: Cell bbox ────────────────────────────────────────────────
    this.map.addLayer({
      id: 'cell-fill',
      type: 'fill',
      source: 'cell-bbox',
      'source-layer': 'raster_coverages',
      minzoom: Z_HEX,
      maxzoom: Z_WMS,
      paint: {
        'fill-color': 'rgba(0, 100, 255, 0.2)',
        'fill-outline-color': 'rgba(0, 100, 255, 0.8)'
      }
    });

    this.map.addLayer({
      id: 'cell-outline',
      type: 'line',
      source: 'cell-bbox',
      'source-layer': 'raster_coverages',
      minzoom: Z_HEX,
      maxzoom: Z_WMS,
      paint: {
        'line-color': '#0064ff',
        'line-width': 1
      }
    });

    this.map.addLayer({
      id: 'cell-label',
      type: 'symbol',
      source: 'cell-bbox',
      'source-layer': 'raster_coverages',
      minzoom: Z_HEX,
      maxzoom: Z_WMS,
      layout: {
        'text-field': ['get', 'cell_id'],
        'text-size': 10,
        'text-allow-overlap': false,
        'text-ignore-placement': false
      },
      paint: {
        'text-color': '#003399',
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.5
      }
    });

    // ── Zoom 13+: WMS raster ─────────────────────────────────────────────────
    this.map.addLayer({
      id: 'wms-raster',
      type: 'raster',
      source: 'wms-binary',
      minzoom: Z_WMS,
      paint: {
        'raster-opacity': 0.85,
        'raster-fade-duration': 200
      }
    });
  }

  // ─── Popup & cursor ────────────────────────────────────────────────────────

  private setupPopup(): void {
    const popup = new maplibregl.Popup({ closeButton: true, maxWidth: '300px' });

    this.map.on('click', 'cell-fill', (e) => {
      if (!e.features?.length) return;
      const p = e.features[0].properties;
      popup
        .setLngLat(e.lngLat)
        .setHTML(`
          <strong>Cell ID:</strong> ${p['cell_id']}<br>
          <strong>File:</strong> ${p['file_path']}<br>
          <strong>CRS:</strong> ${p['crs']}<br>
          <strong>SVR:</strong> ${p['svr'] ? 'Yes' : 'No'}
        `)
        .addTo(this.map);
    });

    this.map.on('click', 'hex-fill', (e) => {
      if (!e.features?.length) return;
      const p = e.features[0].properties;
      popup
        .setLngLat(e.lngLat)
        .setHTML(`
          <strong>Coverage density</strong><br>
          Visible cells: <b>${p['count']}</b><br>
          Total cells: <b>${p['total']}</b>
        `)
        .addTo(this.map);
    });

    ['cell-fill', 'hex-fill'].forEach(layer => {
      this.map.on('mouseenter', layer, () => {
        this.map.getCanvas().style.cursor = 'pointer';
      });
      this.map.on('mouseleave', layer, () => {
        this.map.getCanvas().style.cursor = '';
      });
    });
  }

  // ─── Zoom badge (debug/UX) ─────────────────────────────────────────────────

  private setupZoomBadge(): void {
    this.map.on('zoom', () => {
      const z = this.map.getZoom();
      const badge = document.getElementById('zoom-badge');
      if (!badge) return;
      const Z_HEX = MapComponent.ZOOM_HEX;
      const Z_WMS = MapComponent.ZOOM_WMS;
      if (z < Z_HEX) {
        badge.textContent = `Z${z.toFixed(1)} — Hex heatmap`;
        badge.className = 'zoom-badge hex';
      } else if (z < Z_WMS) {
        badge.textContent = `Z${z.toFixed(1)} — Cell bbox`;
        badge.className = 'zoom-badge bbox';
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
    if (!this.map.loaded()) return;
    const z = this.map.getZoom();
    if (z >= MapComponent.ZOOM_WMS) {
      this.updateWmsSource();
    } else if (z >= MapComponent.ZOOM_HEX) {
      this.applyCellBboxFilter();
    } else {
      this.reloadHexTiles();
    }
  }

  /** Zoom 0–10: bust tile cache bằng timestamp param */
  private reloadHexTiles(): void {
    const src = this.map.getSource('hex-coverage') as VectorTileSource;
    src?.setTiles([
      `${environment.pgTileservUrl}/public.hex_density_tile/{z}/{x}/{y}.pbf?t=${Date.now()}`
    ]);
  }

  /** Zoom 11–12: GPU setFilter — không cần request server */
  private applyCellBboxFilter(): void {
    const hidden = this.hiddenCellIds;
    const filter: FilterSpecification = hidden.length > 0
      ? ['!', ['in', ['get', 'cell_id'], ['literal', hidden]]]
      : ['boolean', true];

    (['cell-fill', 'cell-outline', 'cell-label'] as const).forEach(id => {
      if (this.map.getLayer(id)) {
        this.map.setFilter(id, filter);
      }
    });
  }

  /** Zoom 13+: cập nhật WMS URL với CQL_FILTER mới */
  private updateWmsSource(): void {
    const hiddenFilter = this.hiddenCellIds.length > 0
      ? ` AND cell_id NOT IN (${[...this.hiddenCellIds].sort().map(id => `'${id}'`).join(',')})`
      : '';

    if (hiddenFilter === this.currentCqlFilter) return;
    this.currentCqlFilter = hiddenFilter;

    const src = this.map.getSource('wms-binary') as RasterTileSource;
    src?.setTiles([this.buildWmsUrl(hiddenFilter)]);
  }

  private buildWmsUrl(hiddenFilter: string): string {
    const n = MapComponent.OVERLAP_GROUPS;
    const layers = Array(n).fill('cellcover:binary').join(',');
    const styles = Array(n).fill('cellcover-binary').join(',');
    const cqlFilter = Array.from({ length: n }, (_, g) =>
      `ovlp_group=${g}${hiddenFilter}`
    ).join(';');

    return `${environment.geoServerUrl}/wms?` +
      `SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap` +
      `&LAYERS=${encodeURIComponent(layers)}` +
      `&STYLES=${encodeURIComponent(styles)}` +
      `&FORMAT=image%2Fpng&TRANSPARENT=true` +
      `&CQL_FILTER=${encodeURIComponent(cqlFilter)}` +
      `&WIDTH=512&HEIGHT=512` +
      `&BBOX={bbox-epsg-3857}&SRS=EPSG%3A3857`;
  }
}
