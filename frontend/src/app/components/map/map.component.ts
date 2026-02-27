import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import * as L from 'leaflet';
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

  private static readonly OVERLAP_GROUPS = 8;

  private map!: L.Map;
  private geoJsonLayer!: L.GeoJSON;
  private wmsLayer: L.TileLayer.WMS | null = null;
  private currentCqlFilter: string | null = null;
  private hiddenCellIds: string[] = [];
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

  refreshMap(): void {
    // Reload hidden cells (catches cells hidden outside current viewport)
    this.layerService.getHiddenCellIds().subscribe({
      next: (ids) => {
        this.hiddenCellIds = ids;
        this.updateWmsLayers();
      }
    });
    const bounds = this.map.getBounds();
    this.moveEnd$.next(bounds);
  }

  private initMap(): void {
    this.map = L.map('map', {
      center: [21.028, 105.854],
      zoom: 12
    });

    L.tileLayer('https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', {
      maxZoom: 20,
      attribution: '&copy; Google'
    }).addTo(this.map);

    // Create isolated pane for WMS layers so blend mode only applies among them
    this.map.createPane('wmsPane');
    this.map.getPane('wmsPane')!.style.zIndex = '450';

    this.geoJsonLayer = L.geoJSON(undefined, {
      style: () => ({
        color: '#3388ff',
        weight: 0,
        fillOpacity: 0
      }),
      onEachFeature: (feature, layer) => {
        if (feature.properties) {
          const props = feature.properties;
          layer.bindPopup(`
            <strong>Cell ID:</strong> ${props['cellId']}<br>
            <strong>File:</strong> ${props['filePath']}<br>
            <strong>CRS:</strong> ${props['crs']}<br>
            <strong>SVR:</strong> ${props['svr'] ? 'Yes' : 'No'}
          `);
        }
      }
    }).addTo(this.map);

    this.map.on('moveend', () => {
      this.moveEnd$.next(this.map.getBounds());
    });
  }

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
        this.updateLayers(coverages);
        this.coveragesChanged.emit(coverages);
      },
      error: (err) => console.error('Error fetching layers:', err)
    });

    // Trigger initial load
    setTimeout(() => this.refreshMap(), 100);
  }

  private updateLayers(coverages: RasterCoverage[]): void {
    this.updateGeoJsonLayer(coverages);

    // Cells in viewport that are hidden
    const inViewportIds = new Set(coverages.map(c => c.cellId));
    const inViewportHidden = coverages
      .filter(c => !c.visible)
      .map(c => c.cellId);

    // Preserve cells hidden outside the current viewport
    const outOfViewportHidden = this.hiddenCellIds
      .filter(id => !inViewportIds.has(id));

    this.hiddenCellIds = [...new Set([...inViewportHidden, ...outOfViewportHidden])];
    this.updateWmsLayers();
  }

  private updateGeoJsonLayer(coverages: RasterCoverage[]): void {
    this.geoJsonLayer.clearLayers();

    const features = coverages.map(c => ({
      type: 'Feature' as const,
      geometry: c.bbox,
      properties: {
        id: c.id,
        cellId: c.cellId,
        filePath: c.filePath,
        crs: c.crs,
        svr: c.svr,
        visible: c.visible
      }
    }));

    const featureCollection: GeoJSON.FeatureCollection = {
      type: 'FeatureCollection',
      features
    };

    this.geoJsonLayer.addData(featureCollection);
  }

  private updateWmsLayers(): void {
    // Build hidden-cell filter fragment
    let hiddenFilter = '';
    if (this.hiddenCellIds.length > 0) {
      const escaped = this.hiddenCellIds.map(id => `'${id}'`).join(',');
      hiddenFilter = ` AND cell_id NOT IN (${escaped})`;
    }

    // Skip update if filter hasn't changed
    const filterKey = hiddenFilter;
    if (filterKey === this.currentCqlFilter) return;
    this.currentCqlFilter = filterKey;

    // Remove old layer
    if (this.wmsLayer) {
      this.map.removeLayer(this.wmsLayer);
      this.wmsLayer = null;
    }

    // One WMS request with the layer repeated per overlap group.
    // GeoServer composites sub-layers server-side (source-over alpha),
    // so overlapping areas get progressively more opaque.
    const n = MapComponent.OVERLAP_GROUPS;
    const layers = Array(n).fill('cellcover:binary').join(',');
    const styles = Array(n).fill('cellcover-binary').join(',');
    const cqlParts = Array.from({ length: n }, (_, g) => `ovlp_group=${g}${hiddenFilter}`);
    const cqlFilter = cqlParts.join(';');

    this.wmsLayer = L.tileLayer.wms(`${environment.geoServerUrl}/wms`, {
      layers,
      styles,
      format: 'image/png',
      transparent: true,
      version: '1.1.1',
      pane: 'wmsPane',
      tiled: true,
      tileSize: 512,
      keepBuffer: 4,
      CQL_FILTER: cqlFilter
    } as any);

    // Retry failed tiles
    this.wmsLayer.on('tileerror', (event: any) => {
      const tile = event.tile;
      const src = tile.src;
      if (!tile._retryCount) tile._retryCount = 0;
      if (tile._retryCount < 3) {
        tile._retryCount++;
        setTimeout(() => { tile.src = src; }, 1000 * tile._retryCount);
      }
    });

    this.wmsLayer.addTo(this.map);
  }
}
