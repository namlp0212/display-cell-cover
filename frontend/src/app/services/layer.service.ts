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
}
