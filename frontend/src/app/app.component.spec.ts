import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { Client } from '@stomp/stompjs';

describe('AppComponent', () => {
  beforeEach(async () => {
    // Prevent real WebSocket connections
    spyOn(Client.prototype, 'activate').and.stub();
    spyOn(Client.prototype, 'deactivate').and.stub();

    await TestBed.configureTestingModule({
      imports: [AppComponent, HttpClientTestingModule],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });
});
