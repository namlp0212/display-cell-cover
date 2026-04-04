import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { SimulationService, Simulation, SimulationRequest, SimulationCreateResult } from './simulation.service';
import { environment } from '../../environments/environment';

describe('SimulationService', () => {
  let service: SimulationService;
  let httpMock: HttpTestingController;
  const BASE = `${environment.apiUrl}/simulation`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [SimulationService]
    });
    service = TestBed.inject(SimulationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  // ── listActive ─────────────────────────────────────────────────────────────

  it('listActive() should GET /simulation and return simulation list', () => {
    const mockSims: Simulation[] = [
      { id: 'sim-001', name: 'Bão số 3', status: 'active', createdAt: '2026-04-01T00:00:00Z' },
      { id: 'sim-002', name: 'Mất điện QN', status: 'active', createdAt: '2026-04-02T00:00:00Z' }
    ];

    let result: Simulation[] | undefined;
    service.listActive().subscribe(sims => (result = sims));

    const req = httpMock.expectOne(BASE);
    expect(req.request.method).toBe('GET');
    req.flush(mockSims);

    expect(result).toEqual(mockSims);
    expect(result?.length).toBe(2);
  });

  it('listActive() should return empty array when no active simulations', () => {
    let result: Simulation[] | undefined;
    service.listActive().subscribe(sims => (result = sims));

    const req = httpMock.expectOne(BASE);
    req.flush([]);

    expect(result).toEqual([]);
  });

  // ── get ────────────────────────────────────────────────────────────────────

  it('get() should GET /simulation/{id}', () => {
    const mockSim: Simulation = {
      id: 'sim-001',
      name: 'Bão số 3',
      status: 'active',
      createdAt: '2026-04-01T00:00:00Z'
    };

    let result: Simulation | undefined;
    service.get('sim-001').subscribe(sim => (result = sim));

    const req = httpMock.expectOne(`${BASE}/sim-001`);
    expect(req.request.method).toBe('GET');
    req.flush(mockSim);

    expect(result).toEqual(mockSim);
  });

  // ── create ─────────────────────────────────────────────────────────────────

  it('create() should POST /simulation with request body', () => {
    const req: SimulationRequest = {
      name: 'Bão số 3',
      description: 'Mất điện diện rộng',
      cellsOff: ['CELL001', 'CELL002', 'CELL003']
    };

    const mockResult: SimulationCreateResult = {
      id: 'sim-uuid-001',
      name: 'Bão số 3',
      status: 'active',
      cellsOffCount: 3
    };

    let result: SimulationCreateResult | undefined;
    service.create(req).subscribe(r => (result = r));

    const httpReq = httpMock.expectOne(BASE);
    expect(httpReq.request.method).toBe('POST');
    expect(httpReq.request.body).toEqual(req);
    httpReq.flush(mockResult);

    expect(result?.id).toBe('sim-uuid-001');
    expect(result?.cellsOffCount).toBe(3);
  });

  it('create() with empty cellsOff should work', () => {
    const req: SimulationRequest = { name: 'Test', cellsOff: [] };
    const mockResult: SimulationCreateResult = {
      id: 'sim-empty',
      name: 'Test',
      status: 'active',
      cellsOffCount: 0
    };

    service.create(req).subscribe();
    const httpReq = httpMock.expectOne(BASE);
    httpReq.flush(mockResult);
  });

  // ── end ────────────────────────────────────────────────────────────────────

  it('end() should POST /simulation/{id}/end', () => {
    const endResult = { id: 'sim-001', status: 'ended', endedAt: '2026-04-03T12:00:00Z' };

    let result: unknown;
    service.end('sim-001').subscribe(r => (result = r));

    const req = httpMock.expectOne(`${BASE}/sim-001/end`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(endResult);

    expect(result).toEqual(endResult);
  });

  // ── delete ─────────────────────────────────────────────────────────────────

  it('delete() should DELETE /simulation/{id}', () => {
    let completed = false;
    service.delete('sim-001').subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne(`${BASE}/sim-001`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBeTrue();
  });

  // ── error handling ─────────────────────────────────────────────────────────

  it('get() on unknown simulation should propagate 404 error', () => {
    let error: unknown;
    service.get('unknown').subscribe({ error: (e) => (error = e) });

    const req = httpMock.expectOne(`${BASE}/unknown`);
    req.flush('Not Found', { status: 404, statusText: 'Not Found' });

    expect(error).toBeDefined();
  });
});
