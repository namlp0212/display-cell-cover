import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface Simulation {
  id: string;
  name: string;
  description?: string;
  status: 'active' | 'ended';
  createdAt: string;
  endedAt?: string;
}

export interface SimulationRequest {
  name: string;
  description?: string;
  cellsOff: string[];
}

export interface SimulationCreateResult {
  id: string;
  name: string;
  status: string;
  cellsOffCount: number;
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
}
