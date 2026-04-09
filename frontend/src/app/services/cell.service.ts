import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface CellSearchItem {
  cellId: string;
  cellName: string;
  networkType: string;
  realStatus: boolean;
  stationCode: string;
  stationName: string;
  locationName: string;
  deptName: string;
}

export interface CellSearchPage {
  content: CellSearchItem[];
  totalElements: number;
  page: number;
  size: number;
}

export interface AutocompleteItem {
  type: 'cell' | 'station' | 'dept';
  id: string;
  label: string;
}

export interface CellTreeNode {
  type: 'dept' | 'station' | 'raster' | 'cell';
  id: string;
  name: string;
  networkType?: string;
  realStatus?: boolean;
  totalCells?: number;
  onCells?: number;
  children?: CellTreeNode[];
}

@Injectable({ providedIn: 'root' })
export class CellService {
  private readonly base = `${environment.apiUrl}/cells`;

  constructor(private http: HttpClient) {}

  search(
    q?: string,
    networkType?: string,
    status?: string,
    page = 0,
    size = 50
  ): Observable<CellSearchPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (q)           params = params.set('q', q);
    if (networkType) params = params.set('networkType', networkType);
    if (status)      params = params.set('status', status);
    return this.http.get<CellSearchPage>(`${this.base}/search`, { params });
  }

  autocomplete(q: string, limit = 10): Observable<AutocompleteItem[]> {
    return this.http.get<AutocompleteItem[]>(`${this.base}/autocomplete`, {
      params: new HttpParams().set('q', q).set('limit', limit)
    });
  }

  getTree(networkType?: string): Observable<CellTreeNode[]> {
    let params = new HttpParams();
    if (networkType) params = params.set('networkType', networkType);
    return this.http.get<CellTreeNode[]>(`${this.base}/tree`, { params });
  }
}
