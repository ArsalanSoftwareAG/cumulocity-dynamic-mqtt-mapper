import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse, CookieAuth, ApplicationService, MicroserviceClientRequestAuth } from '@c8y/client';
import { MQTTAuthentication, StatusMessage } from '../mqtt-configuration.model';
import { AnonymousSubject, Subject } from 'rxjs/internal/Subject';
import { Observable, Observer } from 'rxjs';
import { map } from 'rxjs/operators';
import { LoginService } from '@c8y/ngx-components';

@Injectable({ providedIn: 'root' })
export class MQTTConfigurationService {
  private readonly PATH_CONNECT_ENDPOINT = 'connection';

  private readonly PATH_STATUS_ENDPOINT = 'status';

  private readonly PATH_OPERATION_ENDPOINT = 'operation';
  private readonly PATH_MONITORING_ENDPOINT = 'operation';

  private readonly BASE_URL = 'service/generic-mqtt-agent';

  private isMQTTAgentCreated = false;

  private subject: AnonymousSubject<MessageEvent>;
  public messages: Subject<StatusMessage>;

  constructor(
    private client: FetchClient,
    private identity: IdentityService,
    private loginService: LoginService
  ) { }

  async initializeMQTTAgent(): Promise<void> {
    const identity: IExternalIdentity = {
      type: 'c8y_Serial',
      externalId: 'MQTT_AGENT',
    };

    try {
      const { data, res } = await this.identity.detail(identity);
      console.log("Configuration result code: {}", res.status);
      this.isMQTTAgentCreated = true;

    } catch (error) {
      console.error("Configuration result code: {}", error)
      this.isMQTTAgentCreated = false;
    }
  }


  async initializeWebSocket(): Promise<void> {
    try {
      console.log("Cookie", this.loginService.getAuthStrategy());
      let url: string = this.client.getUrl() + "/" + this.BASE_URL;
      let base_url = this.BASE_URL;
      let xsrf = this.getCookieValue('XSRF-TOKEN')
      url = url.replace("http", "ws").replace("https", "wss");
      url = `${url}/${this.BASE_URL}/${this.PATH_MONITORING_ENDPOINT}?XSRF-TOKEN=${xsrf}`;
      console.log("Trying to connect to", url);
      this.messages = <Subject<StatusMessage>>this.connectWebSocket(url).pipe(
        map(
          (response: MessageEvent): StatusMessage => {
            console.log(response.data);
            let data = JSON.parse(response.data)
            return data;
          }
        )
      );
      console.log("Successfully initialized webSocket!");
      this.isMQTTAgentCreated = true;

    } catch (error) {
      console.error("Error when initializing webSocket!", error);
      this.isMQTTAgentCreated = false;
    }
  }

  public connectWebSocket(url: string): AnonymousSubject<MessageEvent> {
    if (!this.subject) {
      this.subject = this.createWebSocket(url);
      console.log("Successfully connected: " + url);
    }
    return this.subject;
  }

  private createWebSocket(url: string): AnonymousSubject<MessageEvent> {
    let ws: WebSocket = new WebSocket(url);
    let observable = new Observable((obs: Observer<MessageEvent>) => {
      ws.onmessage = obs.next.bind(obs);
      ws.onerror = obs.error.bind(obs);
      ws.onclose = obs.complete.bind(obs);
      return ws.close.bind(ws);
    });
    let observer = {
      error: null,
      complete: null,
      next: (data: Object) => {
        console.log('Message sent to websocket: ', data);
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(data));
        }
      }
    };
    return new AnonymousSubject<MessageEvent>(observer, observable);
  }

  private getCookieValue(name: string) {
    console.log("Cookie request:", document, name)
    const value = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
    return value ? value.pop() : '';
  }

  getMQTTAgentCreated(): boolean {
    return this.isMQTTAgentCreated;
  }

  updateConnectionDetails(mqttConfiguration: MQTTAuthentication): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(mqttConfiguration),
      method: 'POST',
    });
  }

  connectToMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "CONNECT" }),
      method: 'POST',
    });
  }

  disconnectFromMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "DISCONNECT" }),
      method: 'POST',
    });
  }

  async getConnectionDetails(): Promise<MQTTAuthentication> {
    const response = await this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        accept: 'application/json',
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as MQTTAuthentication;
  }

  async getConnectionStatus(): Promise<string> {
    const response = await this.client.fetch(`${this.BASE_URL}/${this.PATH_STATUS_ENDPOINT}`, {
      method: 'GET',
    });
    const { status } = await response.json();
    return status;
  }

}
