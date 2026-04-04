import { TestBed } from '@angular/core/testing';
import { AlertService, CellStatusEvent } from './alert.service';
import { Client } from '@stomp/stompjs';

describe('AlertService', () => {
  let service: AlertService;

  beforeEach(() => {
    // Prevent real WebSocket connections in tests
    spyOn(Client.prototype, 'activate').and.stub();
    spyOn(Client.prototype, 'deactivate').and.stub();

    TestBed.configureTestingModule({
      providers: [AlertService]
    });
    service = TestBed.inject(AlertService);
  });

  afterEach(() => {
    service.ngOnDestroy();
  });

  it('should create the service', () => {
    expect(service).toBeTruthy();
  });

  it('should expose cellStatusChanged$ observable', () => {
    expect(service.cellStatusChanged$).toBeDefined();
  });

  it('cellStatusChanged$ should emit CellStatusEvent when next() is called', () => {
    const event: CellStatusEvent = {
      cellId: 'EDN000231',
      status: false,
      timestamp: '2026-04-03T10:30:00Z'
    };

    let received: CellStatusEvent | undefined;
    service.cellStatusChanged$.subscribe(e => (received = e));
    service.cellStatusChanged$.next(event);

    expect(received).toEqual(event);
  });

  it('cellStatusChanged$ should support multiple subscribers', () => {
    const events: CellStatusEvent[] = [];
    service.cellStatusChanged$.subscribe(e => events.push(e));
    service.cellStatusChanged$.subscribe(e => events.push(e));

    service.cellStatusChanged$.next({
      cellId: 'CELL001',
      status: true,
      timestamp: '2026-04-03T11:00:00Z'
    });

    // Both subscribers receive the event
    expect(events.length).toBe(2);
    expect(events[0].cellId).toBe('CELL001');
    expect(events[1].cellId).toBe('CELL001');
  });

  it('cellStatusChanged$ should emit each status update independently', () => {
    const received: CellStatusEvent[] = [];
    service.cellStatusChanged$.subscribe(e => received.push(e));

    service.cellStatusChanged$.next({ cellId: 'CELL001', status: false, timestamp: '2026-04-01T00:00:00Z' });
    service.cellStatusChanged$.next({ cellId: 'CELL002', status: true,  timestamp: '2026-04-02T00:00:00Z' });
    service.cellStatusChanged$.next({ cellId: 'CELL001', status: true,  timestamp: '2026-04-03T00:00:00Z' });

    expect(received.length).toBe(3);
    expect(received[0]).toEqual({ cellId: 'CELL001', status: false, timestamp: '2026-04-01T00:00:00Z' });
    expect(received[1]).toEqual({ cellId: 'CELL002', status: true,  timestamp: '2026-04-02T00:00:00Z' });
    expect(received[2]).toEqual({ cellId: 'CELL001', status: true,  timestamp: '2026-04-03T00:00:00Z' });
  });

  it('ngOnDestroy() should deactivate STOMP client', () => {
    service.ngOnDestroy();
    expect(Client.prototype.deactivate).toHaveBeenCalled();
  });

  it('cellStatusChanged$ should not emit after service is destroyed', () => {
    let emitCount = 0;
    const sub = service.cellStatusChanged$.subscribe(() => emitCount++);

    service.cellStatusChanged$.next({ cellId: 'CELL001', status: false, timestamp: '' });
    expect(emitCount).toBe(1);

    sub.unsubscribe();
    service.cellStatusChanged$.next({ cellId: 'CELL002', status: true, timestamp: '' });
    expect(emitCount).toBe(1);  // No more emissions after unsubscribe
  });
});
