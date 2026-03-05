import { Component, ViewChild } from '@angular/core';
import { MapComponent } from './components/map/map.component';
import { SidebarComponent } from './components/sidebar/sidebar.component';
import { RasterCoverage } from './models/raster-coverage.model';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [MapComponent, SidebarComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  @ViewChild(MapComponent) mapComponent!: MapComponent;

  coverages: RasterCoverage[] = [];

  onCoveragesChanged(coverages: RasterCoverage[]): void {
    if (this.coverages.length === 0) {
      this.coverages = [...coverages].sort((a, b) =>
        a.cellId.localeCompare(b.cellId, undefined, { numeric: true, sensitivity: 'base' })
      );
      return;
    }

    // Merge new data while preserving visibility state
    const newMap = new Map(coverages.map(c => [c.id, c]));
    const oldIds = new Set(this.coverages.map(c => c.id));

    const merged = this.coverages
      .filter(c => newMap.has(c.id))
      .map(c => newMap.get(c.id)!);

    for (const c of coverages) {
      if (!oldIds.has(c.id)) {
        merged.push(c);
      }
    }

    this.coverages = merged.sort((a, b) =>
      a.cellId.localeCompare(b.cellId, undefined, { numeric: true, sensitivity: 'base' })
    );
  }

  onVisibilityToggled(): void {
    this.mapComponent.refreshMap();
  }
}
