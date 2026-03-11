import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { RasterCoverage, ToggleResponse } from '../models/raster-coverage.model';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class LayerService {
  private readonly apiUrl = environment.apiUrl;

  constructor(private http: HttpClient) {}

  getVisibleLayers(minx: number, miny: number, maxx: number, maxy: number): Observable<RasterCoverage[]> {
    const params = new HttpParams()
      .set('minx', minx.toString())
      .set('miny', miny.toString())
      .set('maxx', maxx.toString())
      .set('maxy', maxy.toString());

    return this.http.get<RasterCoverage[]>(`${this.apiUrl}/layers`, { params });
  }

  toggleVisibility(cellId: string, visible: boolean): Observable<ToggleResponse> {
    const params = new HttpParams().set('visible', visible.toString());
    return this.http.post<ToggleResponse>(`${this.apiUrl}/toggle/${cellId}`, null, { params });
  }

  getHiddenCellIds(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/hidden-cells`);
  }

  /**
   * Returns a GeoJSON geometry representing areas covered exclusively by off-cells.
   * The backend computes: ST_Difference(union_of_off_cells, union_of_on_cells).
   * Returns null when there is nothing to render (204 No Content from backend).
   */
  getOffOnlyArea(minx: number, miny: number, maxx: number, maxy: number): Observable<GeoJSON.Geometry | null> {
    const params = new HttpParams()
      .set('minx', minx.toString())
      .set('miny', miny.toString())
      .set('maxx', maxx.toString())
      .set('maxy', maxy.toString());

    return this.http.get<GeoJSON.Geometry | null>(`${this.apiUrl}/off-only-area`, { params });
  }
}
