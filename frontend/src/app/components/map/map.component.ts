import { Component, OnInit, OnDestroy, NgZone } from '@angular/core';
import { NgIf, NgFor, NgClass, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import '@maplibre/maplibre-gl-leaflet';
import { VectorTileSource } from 'maplibre-gl';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SimulationService, Simulation } from '../../services/simulation.service';
import { AlertService } from '../../services/alert.service';
import { CellService, CellSearchItem } from '../../services/cell.service';

export type DisplayMode = 'coverage' | 'signal';

interface TreeCell     { cellId: string; realStatus: boolean; }
interface TreeRaster   { rasterId: number; rasterCode: string; rasterName: string; networkType: string; cells: TreeCell[]; }
interface TreeStation  { stationId: number; stationCode: string; rasters: TreeRaster[]; }
interface TreeDept     { deptId: number; deptCode: string; deptName: string; deptLevel: number; children?: TreeDept[]; stations?: TreeStation[]; }

@Component({
  selector: 'app-map',
  standalone: true,
  imports: [NgIf, NgFor, NgClass, NgTemplateOutlet, FormsModule],
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

  // Network type filter
  activeNetworkTypes: Set<string> = new Set(['2G', '3G', '4G', '5G']);

  // Session toggle state
  sessionSimId: string | null = null;
  sessionCellsOff: Set<string> = new Set();

  // Hierarchy tree sidebar
  treeOpen = true;
  treeLoading = false;
  treeData: TreeDept[] = [];
  expandedDepts    = new Set<number>();
  expandedStations = new Set<number>();
  expandedRasters  = new Set<number>();

  // Tree index maps (built after load) — forward (child→parent)
  private deptParentMap    = new Map<number, number>(); // deptId → parentDeptId
  private stationDeptMap   = new Map<number, number>(); // stationId → deptId
  private rasterStationMap = new Map<number, number>(); // rasterId → stationId
  // Tree index maps — inverse (parent→children), needed to clear descendants
  private deptChildrenMap   = new Map<number, number[]>();  // deptId → childDeptIds
  private deptStationsMap   = new Map<number, number[]>();  // deptId → stationIds
  private stationRastersMap = new Map<number, number[]>();  // stationId → rasterIds
  private rasterCellsMap    = new Map<number, string[]>();  // rasterId → cellIds

  // Scenario mode
  scenarioMode = false;
  scenarioDeptOff    = new Set<number>();
  scenarioStationOff = new Set<number>();
  scenarioRasterOff  = new Set<number>();
  scenarioCellOff    = new Set<string>();
  scenarioApplying   = false;
  scenarioError: string | null = null;
  private scenarioPreviewSimId: string | null = null;
  private scenarioPreviewSubject = new Subject<void>();
  private scenarioErrorTimer: ReturnType<typeof setTimeout> | null = null;

  // Cell search (integrated in sidebar)
  searchQuery = '';
  searchResults: CellSearchItem[] = [];
  searchTotal = 0;
  searchPage = 0;
  searchLoading = false;
  private searchSubject = new Subject<string>();

  private legendControl: L.Control | null = null;

  constructor(
    private simService: SimulationService,
    private alertService: AlertService,
    private cellService: CellService,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.initMap();
    this.loadSimulations();
    this.subscribeAlerts();
    this.initSearch();
    this.initScenarioPreview();
    this.loadTree();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    if (this.scenarioErrorTimer) clearTimeout(this.scenarioErrorTimer);
    this.map?.remove();
  }

  // ── Simulation dropdown ────────────────────────────────────────────────────

  loadSimulations(): void {
    this.simService.listActive().subscribe({
      next: sims => { this.simulations = sims.filter(s => !s.ephemeral); },
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
        if (this.selectedSimId === simId) this.selectedSimId = null;
        this.loadSimulations();
        this.reloadH3Tiles();
      }
    });
  }

  // ── Hierarchy tree sidebar ─────────────────────────────────────────────────

  loadTree(): void {
    this.treeLoading = true;
    this.cellService.getTree().subscribe({
      next: (data: any) => {
        this.treeData = data as TreeDept[];
        this.treeData.forEach(d => this.expandedDepts.add(d.deptId));
        this.buildTreeMaps();
        this.treeLoading = false;
      },
      error: () => { this.treeLoading = false; }
    });
  }

  toggleTreePanel(): void { this.treeOpen = !this.treeOpen; }
  toggleDept(id: number): void {
    this.expandedDepts.has(id) ? this.expandedDepts.delete(id) : this.expandedDepts.add(id);
  }
  toggleStation(id: number): void {
    this.expandedStations.has(id) ? this.expandedStations.delete(id) : this.expandedStations.add(id);
  }
  toggleRaster(id: number): void {
    this.expandedRasters.has(id) ? this.expandedRasters.delete(id) : this.expandedRasters.add(id);
  }

  // ── Tree index maps ────────────────────────────────────────────────────────

  private buildTreeMaps(): void {
    this.deptParentMap.clear();
    this.stationDeptMap.clear();
    this.rasterStationMap.clear();
    this.deptChildrenMap.clear();
    this.deptStationsMap.clear();
    this.stationRastersMap.clear();
    this.rasterCellsMap.clear();
    this.indexDepts(this.treeData, undefined);
  }

  private indexDepts(depts: TreeDept[], parentId: number | undefined): void {
    for (const dept of depts) {
      if (parentId !== undefined) {
        this.deptParentMap.set(dept.deptId, parentId);
        const siblings = this.deptChildrenMap.get(parentId) ?? [];
        siblings.push(dept.deptId);
        this.deptChildrenMap.set(parentId, siblings);
      }
      const stationIds: number[] = [];
      dept.stations?.forEach(s => {
        stationIds.push(s.stationId);
        this.stationDeptMap.set(s.stationId, dept.deptId);
        const rasterIds: number[] = [];
        s.rasters.forEach(r => {
          rasterIds.push(r.rasterId);
          this.rasterStationMap.set(r.rasterId, s.stationId);
          this.rasterCellsMap.set(r.rasterId, r.cells.map(c => c.cellId));
        });
        this.stationRastersMap.set(s.stationId, rasterIds);
      });
      this.deptStationsMap.set(dept.deptId, stationIds);
      if (dept.children) this.indexDepts(dept.children, dept.deptId);
    }
  }

  // ── Scenario: effective OFF checks ────────────────────────────────────────

  isDeptOff(deptId: number): boolean {
    if (this.scenarioDeptOff.has(deptId)) return true;
    const parentId = this.deptParentMap.get(deptId);
    return parentId !== undefined && this.isDeptOff(parentId);
  }

  isDeptAncestorOff(deptId: number): boolean {
    const parentId = this.deptParentMap.get(deptId);
    return parentId !== undefined && this.isDeptOff(parentId);
  }

  isStationOff(stationId: number): boolean {
    if (this.scenarioStationOff.has(stationId)) return true;
    const deptId = this.stationDeptMap.get(stationId);
    return deptId !== undefined && this.isDeptOff(deptId);
  }

  isStationAncestorOff(stationId: number): boolean {
    const deptId = this.stationDeptMap.get(stationId);
    return deptId !== undefined && this.isDeptOff(deptId);
  }

  isRasterOff(rasterId: number): boolean {
    if (this.scenarioRasterOff.has(rasterId)) return true;
    const stationId = this.rasterStationMap.get(rasterId);
    return stationId !== undefined && this.isStationOff(stationId);
  }

  isRasterAncestorOff(rasterId: number): boolean {
    const stationId = this.rasterStationMap.get(rasterId);
    return stationId !== undefined && this.isStationOff(stationId);
  }

  isCellOff(cellId: string, rasterId: number): boolean {
    return this.isRasterOff(rasterId) || this.scenarioCellOff.has(cellId);
  }

  isCellAncestorOff(rasterId: number): boolean {
    return this.isRasterOff(rasterId);
  }

  // ── Scenario: clear descendants (called when toggling a parent back ON) ──────

  private clearRasterDescendants(rasterId: number): void {
    this.rasterCellsMap.get(rasterId)?.forEach(cId => this.scenarioCellOff.delete(cId));
  }

  private clearStationDescendants(stationId: number): void {
    this.stationRastersMap.get(stationId)?.forEach(rId => {
      this.scenarioRasterOff.delete(rId);
      this.clearRasterDescendants(rId);
    });
  }

  private clearDeptDescendants(deptId: number): void {
    this.deptStationsMap.get(deptId)?.forEach(sId => {
      this.scenarioStationOff.delete(sId);
      this.clearStationDescendants(sId);
    });
    this.deptChildrenMap.get(deptId)?.forEach(childId => {
      this.scenarioDeptOff.delete(childId);
      this.clearDeptDescendants(childId);
    });
  }

  // ── Scenario: level toggles ────────────────────────────────────────────────

  toggleScenarioDept(deptId: number): void {
    if (this.isDeptAncestorOff(deptId)) return;
    if (this.scenarioDeptOff.has(deptId)) {
      this.scenarioDeptOff.delete(deptId);
      this.clearDeptDescendants(deptId);
    } else {
      this.scenarioDeptOff.add(deptId);
    }
    this.scenarioPreviewSubject.next();
  }

  toggleScenarioStation(stationId: number): void {
    if (this.isStationAncestorOff(stationId)) return;
    if (this.scenarioStationOff.has(stationId)) {
      this.scenarioStationOff.delete(stationId);
      this.clearStationDescendants(stationId);
    } else {
      this.scenarioStationOff.add(stationId);
    }
    this.scenarioPreviewSubject.next();
  }

  toggleScenarioRaster(rasterId: number): void {
    if (this.isRasterAncestorOff(rasterId)) return;
    if (this.scenarioRasterOff.has(rasterId)) {
      this.scenarioRasterOff.delete(rasterId);
      this.clearRasterDescendants(rasterId);
    } else {
      this.scenarioRasterOff.add(rasterId);
    }
    this.scenarioPreviewSubject.next();
  }

  toggleScenarioCell(cellId: string, rasterId: number): void {
    if (this.isCellAncestorOff(rasterId)) return;
    this.scenarioCellOff.has(cellId)
      ? this.scenarioCellOff.delete(cellId)
      : this.scenarioCellOff.add(cellId);
    this.scenarioPreviewSubject.next();
  }

  // ── Scenario: lifecycle ────────────────────────────────────────────────────

  enterScenarioMode(): void {
    this.scenarioMode = true;
    this.scenarioDeptOff.clear();
    this.scenarioStationOff.clear();
    this.scenarioRasterOff.clear();
    this.scenarioCellOff.clear();
    // Remove any previously selected simulation so the map starts from real state
    this.selectedSimId = null;
    // scenarioPreviewSimId is null until first toggle — no &sim= in tile URL
    this.scenarioPreviewSimId = null;
    this.reloadH3Tiles();
  }

  cancelScenarioMode(): void {
    this.scenarioMode = false;
    this.scenarioDeptOff.clear();
    this.scenarioStationOff.clear();
    this.scenarioRasterOff.clear();
    this.scenarioCellOff.clear();
    if (this.scenarioPreviewSimId) {
      this.simService.deleteSession(this.scenarioPreviewSimId).subscribe();
      if (this.selectedSimId === this.scenarioPreviewSimId) this.selectedSimId = null;
      this.scenarioPreviewSimId = null;
      this.reloadH3Tiles();
    }
  }

  saveScenario(): void {
    const name = prompt('Tên kịch bản:');
    if (!name?.trim()) return;

    if (this.scenarioPreviewSimId) {
      // Promote the existing preview session directly
      this.simService.saveSession(this.scenarioPreviewSimId, name.trim()).subscribe({
        next: sim => {
          this.scenarioPreviewSimId = null;
          this.scenarioMode = false;
          this.scenarioDeptOff.clear();
          this.scenarioStationOff.clear();
          this.scenarioRasterOff.clear();
          this.scenarioCellOff.clear();
          this.selectedSimId = sim.id;
          this.loadSimulations();
          this.reloadH3Tiles();
        }
      });
    } else {
      const cellsOff = this.collectScenarioCellsOff();
      this.simService.createSession(name.trim(), cellsOff).subscribe({
        next: session => {
          this.simService.saveSession(session.id, name.trim()).subscribe({
            next: sim => {
              this.cancelScenarioMode();
              this.selectedSimId = sim.id;
              this.loadSimulations();
              this.reloadH3Tiles();
            }
          });
        }
      });
    }
  }

  // ── Scenario: live map preview ─────────────────────────────────────────────

  private initScenarioPreview(): void {
    this.subscriptions.add(
      this.scenarioPreviewSubject.pipe(debounceTime(300)).subscribe(() => {
        this.applyScenarioPreview();
      })
    );
  }

  private applyScenarioPreview(): void {
    const cellsOff = this.collectScenarioCellsOff();

    if (cellsOff.length === 0) {
      if (this.scenarioPreviewSimId) {
        this.simService.deleteSession(this.scenarioPreviewSimId).subscribe();
        if (this.selectedSimId === this.scenarioPreviewSimId) this.selectedSimId = null;
        this.scenarioPreviewSimId = null;
      }
      this.reloadH3Tiles();
      return;
    }

    this.scenarioApplying = true;
    this.scenarioError = null;

    const done = () => { this.scenarioApplying = false; };
    const fail = (err: unknown) => {
      this.scenarioApplying = false;
      this.showScenarioError('Cập nhật thất bại, vui lòng thử lại.');
      console.error('[scenario] applyScenarioPreview failed', err);
    };

    if (!this.scenarioPreviewSimId) {
      this.simService.createSession(`_scenario_preview_${Date.now()}`, cellsOff).subscribe({
        next: result => {
          this.scenarioPreviewSimId = result.id;
          this.reloadH3Tiles();
          done();
        },
        error: fail
      });
    } else {
      this.simService.updateSession(this.scenarioPreviewSimId, cellsOff).subscribe({
        next: () => { this.reloadH3Tiles(); done(); },
        error: fail
      });
    }
  }

  private showScenarioError(msg: string): void {
    this.scenarioError = msg;
    if (this.scenarioErrorTimer) clearTimeout(this.scenarioErrorTimer);
    this.scenarioErrorTimer = setTimeout(() => {
      this.scenarioError = null;
      this.scenarioErrorTimer = null;
    }, 4000);
  }

  private collectScenarioCellsOff(): string[] {
    const result: string[] = [];
    this.walkForOffCells(this.treeData, result);
    return result;
  }

  private walkForOffCells(depts: TreeDept[], result: string[]): void {
    for (const dept of depts) {
      dept.stations?.forEach(station => {
        station.rasters.forEach(raster => {
          if (this.isRasterOff(raster.rasterId)) {
            // Entire raster (and its ancestors) is off → all cells off
            raster.cells.forEach(c => result.push(c.cellId));
          } else {
            // Raster is on → check individual cell toggles
            raster.cells.forEach(c => {
              if (this.scenarioCellOff.has(c.cellId)) result.push(c.cellId);
            });
          }
        });
      });
      if (dept.children) this.walkForOffCells(dept.children, result);
    }
  }

  // ── Network type filter ────────────────────────────────────────────────────

  toggleNetworkType(type: string): void {
    if (this.activeNetworkTypes.has(type)) {
      if (this.activeNetworkTypes.size > 1) this.activeNetworkTypes.delete(type);
    } else {
      this.activeNetworkTypes.add(type);
    }
    this.reloadH3Tiles();
  }

  private networkTypesParam(): string | null {
    const all = ['2G', '3G', '4G', '5G'];
    if (this.activeNetworkTypes.size === all.length) return null;
    return [...this.activeNetworkTypes].sort().join(',');
  }

  // ── Session toggle ─────────────────────────────────────────────────────────

  toggleCellSession(cellId: string): void {
    const wasOff = this.sessionCellsOff.has(cellId);
    wasOff ? this.sessionCellsOff.delete(cellId) : this.sessionCellsOff.add(cellId);

    const cellsOff = [...this.sessionCellsOff];

    if (cellsOff.length === 0) {
      if (this.sessionSimId) {
        this.simService.deleteSession(this.sessionSimId).subscribe({
          next: () => { this.sessionSimId = null; this.reloadH3Tiles(); }
        });
      }
      return;
    }

    if (!this.sessionSimId) {
      this.simService.createSession(`_session_${Date.now()}`, cellsOff).subscribe({
        next: result => {
          this.sessionSimId = result.id;
          this.selectedSimId = result.id;
          this.reloadH3Tiles();
        }
      });
    } else {
      this.simService.updateSession(this.sessionSimId, cellsOff).subscribe({
        next: () => this.reloadH3Tiles()
      });
    }
  }

  resetSession(): void {
    if (!this.sessionSimId) return;
    this.simService.deleteSession(this.sessionSimId).subscribe({
      next: () => {
        this.sessionSimId = null;
        this.sessionCellsOff.clear();
        if (this.selectedSimId === this.sessionSimId) this.selectedSimId = null;
        this.reloadH3Tiles();
      }
    });
  }

  saveSessionAsSim(name: string): void {
    if (!this.sessionSimId) return;
    this.simService.saveSession(this.sessionSimId, name).subscribe({
      next: sim => {
        this.sessionSimId = null;
        this.sessionCellsOff.clear();
        this.selectedSimId = sim.id;
        this.loadSimulations();
        this.reloadH3Tiles();
      }
    });
  }

  promptSaveSession(): void {
    const name = prompt('Tên kịch bản:');
    if (name?.trim()) this.saveSessionAsSim(name.trim());
  }

  // ── Cell search ────────────────────────────────────────────────────────────

  private initSearch(): void {
    this.subscriptions.add(
      this.searchSubject.pipe(debounceTime(300), distinctUntilChanged()).subscribe(q => {
        this.searchPage = 0;
        this.runSearch(q);
      })
    );
  }

  onSearchInput(q: string): void {
    this.searchQuery = q;
    this.searchSubject.next(q);
  }

  /** Normalize free-text query for approximate cell_id matching.
   *  Spaces → % wildcard so "BTN 0012" → "BTN%0012" → LIKE %BTN%0012%. */
  private normalizeQuery(q: string): string {
    return q.trim().replace(/\s+/g, '%');
  }

  private runSearch(q: string): void {
    if (!q.trim()) { this.searchResults = []; this.searchTotal = 0; return; }
    this.searchLoading = true;
    const normalized = this.normalizeQuery(q);
    this.cellService.search(normalized, undefined, undefined, this.searchPage, 50).subscribe({
      next: page => {
        this.searchResults = page.content;
        this.searchTotal = page.totalElements;
        this.searchLoading = false;
      },
      error: () => { this.searchLoading = false; }
    });
  }

  zoomToCell(cell: CellSearchItem): void {
    console.log('Zoom to cell:', cell.cellId);
  }

  // ── Alert WebSocket ────────────────────────────────────────────────────────

  private subscribeAlerts(): void {
    this.subscriptions.add(
      this.alertService.cellStatusChanged$.subscribe(() => {
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
    this.addTreeButton();
  }

  // ── Tile URLs ──────────────────────────────────────────────────────────────

  private h3TileUrl(): string {
    let url = `${environment.apiUrl}/h3-tile/{z}/{x}/{y}?mode=${this.displayMode}&t=${Date.now()}`;
    const nt = this.networkTypesParam();
    if (nt) url += `&networkTypes=${encodeURIComponent(nt)}`;
    if (this.scenarioMode) {
      url += '&baseline=true';
      if (this.scenarioPreviewSimId) url += `&sim=${this.scenarioPreviewSimId}`;
    } else {
      const simId = this.sessionSimId ?? this.selectedSimId;
      if (simId) url += `&sim=${simId}`;
    }
    return url;
  }

  private pixelTileUrl(): string {
    let url = `${environment.apiUrl}/h3-tile/{z}/{x}/{y}?mode=${this.displayMode}&t=${Date.now()}`;
    const nt = this.networkTypesParam();
    if (nt) url += `&networkTypes=${encodeURIComponent(nt)}`;
    if (this.scenarioMode) {
      url += '&baseline=true';
      if (this.scenarioPreviewSimId) url += `&sim=${this.scenarioPreviewSimId}`;
    } else {
      const simId = this.sessionSimId ?? this.selectedSimId;
      if (simId) url += `&sim=${simId}`;
    }
    return url;
  }

  private reloadH3Tiles(): void {
    const glMap = this.glLayer?.getMaplibreMap();
    if (!glMap) return;

    // isStyleLoaded() is enough — loaded() also waits for all tiles to finish
    // which returns false while tiles are still rendering, causing premature exit.
    if (!glMap.isStyleLoaded()) {
      glMap.once('styledata', () => this.reloadH3Tiles());
      return;
    }

    const h3Src = glMap.getSource('h3-coverage') as VectorTileSource;
    h3Src?.setTiles([this.h3TileUrl()]);

    const pixelSrc = glMap.getSource('pixel-coverage') as any;
    pixelSrc?.setTiles([this.pixelTileUrl()]);
  }

  // ── Display mode ───────────────────────────────────────────────────────────

  setDisplayMode(mode: DisplayMode): void {
    if (this.displayMode === mode) return;
    this.displayMode = mode;
    this.rebuildLegend();
    const glMap = this.glLayer?.getMaplibreMap();
    if (glMap?.getLayer('h3-fill')) this.updateLayerPaint(glMap);
    this.reloadH3Tiles();
  }

  // ── Paint expressions ──────────────────────────────────────────────────────

  private buildFillPaint(): object {
    const o = this.wmsOpacity;
    if (this.displayMode === 'signal') {
      return {
        'fill-color': [
          'case',
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
    if (glMap.getLayer('pixel-raster')) {
      glMap.setPaintProperty('pixel-raster', 'raster-opacity', this.wmsOpacity);
    }
  }

  // ── Popup ──────────────────────────────────────────────────────────────────

  private setupPopup(): void {
    const glMap = this.glLayer.getMaplibreMap();
    const popup = L.popup({ maxWidth: 320 });

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

    glMap.on('mouseenter', 'h3-fill', () => { glMap.getCanvas().style.cursor = 'pointer'; });
    glMap.on('mouseleave', 'h3-fill', () => { glMap.getCanvas().style.cursor = ''; });
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
          <div class="network-filter-row">
            <span class="net-filter-label">Mạng:</span>
            ${['2G','3G','4G','5G'].map(t => `
              <label class="net-chip ${self.activeNetworkTypes.has(t) ? 'active' : ''}" data-nt="${t}">
                <input type="checkbox" ${self.activeNetworkTypes.has(t) ? 'checked' : ''} data-nt="${t}" style="display:none">
                ${t}
              </label>`).join('')}
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

        div.querySelectorAll('.net-chip').forEach(chip => {
          L.DomEvent.on(chip as HTMLElement, 'click', e => {
            L.DomEvent.stopPropagation(e);
            const nt = (chip as HTMLElement).dataset['nt']!;
            self.toggleNetworkType(nt);
            self.activeNetworkTypes.has(nt)
              ? chip.classList.add('active')
              : chip.classList.remove('active');
          });
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
          <div class="session-row" style="display:none">
            <span class="session-badge">⚡ Session</span>
            <span class="session-cells-count">0 cells OFF</span>
            <button class="session-reset-btn" title="Bỏ tất cả toggle">✕ Reset</button>
            <button class="session-save-btn" title="Lưu thành kịch bản">💾 Lưu</button>
          </div>
        `;

        const select     = div.querySelector('.sim-select')         as HTMLSelectElement;
        const refreshBtn = div.querySelector('.sim-refresh')         as HTMLButtonElement;
        const sessionRow = div.querySelector('.session-row')         as HTMLElement;
        const cellsCount = div.querySelector('.session-cells-count') as HTMLElement;
        const resetBtn   = div.querySelector('.session-reset-btn')   as HTMLButtonElement;
        const saveBtn    = div.querySelector('.session-save-btn')    as HTMLButtonElement;

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

        function updateSessionRow(): void {
          if (self.sessionCellsOff.size > 0) {
            sessionRow.style.display = 'flex';
            cellsCount.textContent = `${self.sessionCellsOff.size} cell${self.sessionCellsOff.size !== 1 ? 's' : ''} OFF`;
          } else {
            sessionRow.style.display = 'none';
          }
        }

        let lastSimCount = 0;
        let lastSessionSize = -1;
        const intervalId = setInterval(() => {
          if (self.simulations.length !== lastSimCount) {
            lastSimCount = self.simulations.length;
            repopulate();
          }
          if (self.sessionCellsOff.size !== lastSessionSize) {
            lastSessionSize = self.sessionCellsOff.size;
            updateSessionRow();
          }
        }, 500);

        (div as any)['_clearInterval'] = () => clearInterval(intervalId);

        L.DomEvent.on(select, 'change', () => self.selectSim(select.value || null));
        L.DomEvent.on(refreshBtn, 'click', e => {
          L.DomEvent.stopPropagation(e);
          self.loadSimulations();
        });
        L.DomEvent.on(resetBtn, 'click', e => {
          L.DomEvent.stopPropagation(e);
          self.resetSession();
        });
        L.DomEvent.on(saveBtn, 'click', e => {
          L.DomEvent.stopPropagation(e);
          const name = prompt('Tên kịch bản:');
          if (name?.trim()) self.saveSessionAsSim(name.trim());
        });

        L.DomEvent.disableClickPropagation(div);
        L.DomEvent.disableScrollPropagation(div);
        return div;
      },
      onRemove(): void { /* clearInterval handled by _clearInterval */ }
    });
    new SimControl({ position: 'topleft' }).addTo(this.map);
  }

  // ── Tree sidebar toggle button (Leaflet control) ───────────────────────────

  private addTreeButton(): void {
    const self = this;
    const TreeControl = L.Control.extend({
      onAdd(): HTMLElement {
        const div = L.DomUtil.create('div', 'tree-btn-control');
        div.innerHTML = `<button class="tree-open-btn" title="Cây đơn vị">🌲 Cây</button>`;
        L.DomEvent.on(div.querySelector('.tree-open-btn')!, 'click', e => {
          L.DomEvent.stopPropagation(e);
          self.ngZone.run(() => { self.treeOpen = true; });
        });
        L.DomEvent.disableClickPropagation(div);
        return div;
      }
    });
    new TreeControl({ position: 'topleft' }).addTo(this.map);
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
