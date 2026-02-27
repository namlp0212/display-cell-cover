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
      this.coverages = coverages;
      return;
    }

    // Merge new data while preserving existing order
    const newMap = new Map(coverages.map(c => [c.id, c]));
    const oldIds = new Set(this.coverages.map(c => c.id));

    // Update existing items in place, keep order
    const merged = this.coverages
      .filter(c => newMap.has(c.id))
      .map(c => newMap.get(c.id)!);

    // Append any new coverages that weren't in the old list
    for (const c of coverages) {
      if (!oldIds.has(c.id)) {
        merged.push(c);
      }
    }

    this.coverages = merged;
  }

  onVisibilityToggled(): void {
    this.mapComponent.refreshMap();
  }
}
