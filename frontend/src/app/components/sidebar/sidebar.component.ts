import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RasterCoverage } from '../../models/raster-coverage.model';
import { LayerService } from '../../services/layer.service';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss'
})
export class SidebarComponent {
  @Input() coverages: RasterCoverage[] = [];
  @Output() visibilityToggled = new EventEmitter<void>();

  private togglingCells = new Set<string>();

  constructor(private layerService: LayerService) {}

  isToggling(cellId: string): boolean {
    return this.togglingCells.has(cellId);
  }

  onToggle(coverage: RasterCoverage): void {
    const newVisible = !coverage.visible;
    coverage.visible = newVisible; // optimistic update
    this.togglingCells.add(coverage.cellId);

    this.layerService.toggleVisibility(coverage.cellId, newVisible).subscribe({
      next: () => {
        this.togglingCells.delete(coverage.cellId);
        this.visibilityToggled.emit();
      },
      error: (err) => {
        console.error('Error toggling visibility:', err);
        coverage.visible = !newVisible; // revert on error
        this.togglingCells.delete(coverage.cellId);
      }
    });
  }
}
