import { Injectable, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { Client, IMessage } from '@stomp/stompjs';
import { environment } from '../../environments/environment';

export interface CellStatusEvent {
  cellId: string;
  status: boolean;
  timestamp: string;
}

/**
 * Maintains a STOMP/WebSocket connection to the backend and emits
 * cell-status-changed events so the map can refresh tiles on demand.
 *
 * Uses the native (non-SockJS) /ws-native endpoint.
 */
@Injectable({ providedIn: 'root' })
export class AlertService implements OnDestroy {

  private client: Client;
  readonly cellStatusChanged$ = new Subject<CellStatusEvent>();

  constructor() {
    const wsUrl = environment.apiUrl
      .replace(/^http/, 'ws')        // http → ws  |  https → wss
      .replace(/\/api$/, '/ws-native');

    this.client = new Client({
      brokerURL: wsUrl,
      reconnectDelay: 5000,
      onConnect: () => {
        this.client.subscribe('/topic/cell-status', (msg: IMessage) => {
          try {
            const event: CellStatusEvent = JSON.parse(msg.body);
            this.cellStatusChanged$.next(event);
          } catch { /* ignore malformed */ }
        });
      }
    });

    this.client.activate();
  }

  ngOnDestroy(): void {
    this.client.deactivate();
  }
}
