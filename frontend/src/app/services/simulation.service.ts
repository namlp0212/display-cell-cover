import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface Simulation {
  id: string;
  name: string;
  description?: string;
  status: 'active' | 'ended';
  ephemeral?: boolean;
  cellsOffCount?: number;
  createdAt: string;
  endedAt?: string;
}

export interface SimulationRequest {
  name: string;
  description?: string;
  cellsOff: string[];
  stationsOff?: string[];
}

export interface SimulationCreateResult {
  id: string;
  name: string;
  status: string;
  cellsOffCount: number;
}

export interface SessionCreateResult {
  id: string;
  name: string;
  cellsOffCount: number;
}

export interface UpdateCellsResult {
  cellsOffCount: number;
  added: number;
  removed: number;
}

@Injectable({ providedIn: 'root' })
export class SimulationService {
  private readonly base = `${environment.apiUrl}/simulation`;

  constructor(private http: HttpClient) {}

  listActive(): Observable<Simulation[]> {
    return this.http.get<Simulation[]>(this.base);
  }

  get(id: string): Observable<Simulation> {
    return this.http.get<Simulation>(`${this.base}/${id}`);
  }

  create(req: SimulationRequest): Observable<SimulationCreateResult> {
    return this.http.post<SimulationCreateResult>(this.base, req);
  }

  end(id: string): Observable<unknown> {
    return this.http.post(`${this.base}/${id}/end`, null);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /** Update cells_off in an existing simulation */
  updateCells(id: string, addCellsOff: string[], removeCellsOff: string[]): Observable<UpdateCellsResult> {
    return this.http.put<UpdateCellsResult>(`${this.base}/${id}/cells`, { addCellsOff, removeCellsOff });
  }

  // ── Session (ephemeral) simulation ────────────────────────────────────────

  createSession(name: string, cellsOff: string[]): Observable<SessionCreateResult> {
    return this.http.post<SessionCreateResult>(`${this.base}/session`, { name, cellsOff });
  }

  updateSession(id: string, cellsOff: string[]): Observable<UpdateCellsResult> {
    return this.http.put<UpdateCellsResult>(`${this.base}/session/${id}`, { cellsOff });
  }

  deleteSession(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/session/${id}`);
  }

  saveSession(id: string, name: string, description?: string): Observable<Simulation> {
    return this.http.post<Simulation>(`${this.base}/session/${id}/save`, { name, description });
  }
}
