/**
 * WebSocket client for real-time updates.
 */
class WebSocketClient {
    constructor() {
        this.ws = null;
        this.reconnectInterval = 3000;
        this.listeners = new Map();
        this.connected = false;
    }
    
    connect() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws`;
        
        console.log('Connecting to WebSocket:', wsUrl);
        
        try {
            this.ws = new WebSocket(wsUrl);
            
            this.ws.onopen = () => {
                console.log('WebSocket connected');
                this.connected = true;
                this.updateStatus(true);
                this.emit('connected');
            };
            
            this.ws.onmessage = (event) => {
                try {
                    const message = JSON.parse(event.data);
                    console.log('WebSocket message:', message);
                    this.emit(message.type, message);
                } catch (e) {
                    console.error('Failed to parse message:', e);
                }
            };
            
            this.ws.onclose = () => {
                console.log('WebSocket disconnected');
                this.connected = false;
                this.updateStatus(false);
                this.emit('disconnected');
                
                // Reconnect after delay
                setTimeout(() => this.connect(), this.reconnectInterval);
            };
            
            this.ws.onerror = (error) => {
                console.error('WebSocket error:', error);
                this.emit('error', error);
            };
            
        } catch (e) {
            console.error('Failed to create WebSocket:', e);
            setTimeout(() => this.connect(), this.reconnectInterval);
        }
    }
    
    send(type, data) {
        if (this.connected && this.ws.readyState === WebSocket.OPEN) {
            const message = {
                type: type,
                data: data,
                timestamp: new Date().toISOString()
            };
            this.ws.send(JSON.stringify(message));
        } else {
            console.warn('WebSocket not connected, cannot send message');
        }
    }
    
    on(event, callback) {
        if (!this.listeners.has(event)) {
            this.listeners.set(event, []);
        }
        this.listeners.get(event).push(callback);
    }
    
    off(event, callback) {
        if (this.listeners.has(event)) {
            const callbacks = this.listeners.get(event);
            const index = callbacks.indexOf(callback);
            if (index > -1) {
                callbacks.splice(index, 1);
            }
        }
    }
    
    emit(event, data) {
        if (this.listeners.has(event)) {
            this.listeners.get(event).forEach(callback => {
                try {
                    callback(data);
                } catch (e) {
                    console.error(`Error in listener for ${event}:`, e);
                }
            });
        }
    }
    
    updateStatus(connected) {
        const statusElement = document.getElementById('ws-status');
        if (statusElement) {
            if (connected) {
                statusElement.textContent = 'Connected';
                statusElement.className = 'status-badge connected';
            } else {
                statusElement.textContent = 'Disconnected';
                statusElement.className = 'status-badge disconnected';
            }
        }
    }
    
    disconnect() {
        if (this.ws) {
            this.ws.close();
        }
    }
}

// Create global instance
const wsClient = new WebSocketClient();

// Connect on page load
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => wsClient.connect());
} else {
    wsClient.connect();
}
