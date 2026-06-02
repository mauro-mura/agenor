/**
 * Main application JavaScript for Jentic Web Console.
 */

// API base URL
const API_BASE = '/api';

// State
let agents = [];
let stats = null;
let eventLog = [];

/**
 * Fetch data from API.
 */
async function fetchAPI(endpoint) {
    try {
        const response = await fetch(`${API_BASE}${endpoint}`);
        const result = await response.json();
        
        if (result.success) {
            return result.data;
        } else {
            throw new Error(result.error || 'API request failed');
        }
    } catch (error) {
        console.error('API error:', error);
        throw error;
    }
}

/**
 * Post data to API.
 */
async function postAPI(endpoint) {
    try {
        const response = await fetch(`${API_BASE}${endpoint}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        const result = await response.json();
        
        if (result.success) {
            return result.data;
        } else {
            throw new Error(result.error || 'API request failed');
        }
    } catch (error) {
        console.error('API error:', error);
        throw error;
    }
}

/**
 * Load and display agents.
 */
async function loadAgents() {
    try {
        agents = await fetchAPI('/agents');
        renderAgents();
    } catch (error) {
        showError('Failed to load agents: ' + error.message);
    }
}

/**
 * Load and display statistics.
 */
async function loadStats() {
    try {
        stats = await fetchAPI('/stats');
        renderStats();
    } catch (error) {
        console.error('Failed to load stats:', error);
    }
}

/**
 * Load health status.
 */
async function loadHealth() {
    try {
        const health = await fetchAPI('/health');
        renderHealth(health);
    } catch (error) {
        console.error('Failed to load health:', error);
    }
}

/**
 * Render agents list.
 */
function renderAgents() {
    const container = document.getElementById('agents-list');
    
    if (!agents || agents.length === 0) {
        container.innerHTML = '<div class="loading">No agents found</div>';
        return;
    }
    
    container.innerHTML = agents.map(agent => `
        <div class="agent-card" data-agent-id="${agent.id}">
            <div class="agent-info">
                <h3>
                    <span class="agent-status ${agent.running ? 'running' : 'stopped'}"></span>
                    ${agent.name || agent.id}
                </h3>
                <div class="agent-meta">
                    <span>ID: ${agent.id}</span>
                    <span>Type: ${agent.type || 'Agent'}</span>
                    <span>Status: ${agent.running ? 'Running' : 'Stopped'}</span>
                </div>
            </div>
            <div class="agent-actions">
                ${agent.running 
                    ? `<button class="btn btn-danger" onclick="stopAgent('${agent.id}')">Stop</button>`
                    : `<button class="btn btn-success" onclick="startAgent('${agent.id}')">Start</button>`
                }
            </div>
        </div>
    `).join('');
}

/**
 * Render statistics.
 */
function renderStats() {
    if (!stats) return;
    
    document.getElementById('total-agents').textContent = stats.totalAgents || 0;
    document.getElementById('active-agents').textContent = stats.activeAgents || 0;
    
    if (stats.runtime) {
        const memoryPercent = Math.round(
            (stats.runtime.usedMemoryMB / stats.runtime.totalMemoryMB) * 100
        );
        document.getElementById('memory-usage').textContent = 
            `${stats.runtime.usedMemoryMB}MB (${memoryPercent}%)`;
    }
}

/**
 * Render health status.
 */
function renderHealth(health) {
    const statusElement = document.getElementById('health-status');
    if (statusElement) {
        statusElement.textContent = health.status;
        statusElement.style.color = health.status === 'UP' ? 'var(--success)' : 'var(--warning)';
    }
}

/**
 * Start an agent.
 */
async function startAgent(agentId) {
    try {
        await postAPI(`/agents/${agentId}/start`);
        addEvent('success', `Agent ${agentId} started`);
        await loadAgents();
    } catch (error) {
        addEvent('error', `Failed to start agent ${agentId}: ${error.message}`);
    }
}

/**
 * Stop an agent.
 */
async function stopAgent(agentId) {
    try {
        await postAPI(`/agents/${agentId}/stop`);
        addEvent('success', `Agent ${agentId} stopped`);
        await loadAgents();
    } catch (error) {
        addEvent('error', `Failed to stop agent ${agentId}: ${error.message}`);
    }
}

/**
 * Add event to log.
 */
function addEvent(type, message) {
    const timestamp = new Date().toLocaleTimeString();
    eventLog.unshift({ type, message, timestamp });
    
    // Keep only last 50 events
    if (eventLog.length > 50) {
        eventLog = eventLog.slice(0, 50);
    }
    
    renderEvents();
}

/**
 * Render events log.
 */
function renderEvents() {
    const container = document.getElementById('events-log');
    
    if (eventLog.length === 0) {
        container.innerHTML = '<div class="event">No events yet</div>';
        return;
    }
    
    container.innerHTML = eventLog.map(event => `
        <div class="event ${event.type}">
            <span class="event-time">${event.timestamp}</span>
            ${event.message}
        </div>
    `).join('');
}

/**
 * Show error message.
 */
function showError(message) {
    addEvent('error', message);
}

/**
 * Refresh all data.
 */
async function refreshAll() {
    await Promise.all([
        loadAgents(),
        loadStats(),
        loadHealth()
    ]);
}

/**
 * Clear events log.
 */
function clearEvents() {
    eventLog = [];
    renderEvents();
}

// Event listeners
document.addEventListener('DOMContentLoaded', () => {
    // Initial load
    refreshAll();
    
    // Refresh button
    const refreshBtn = document.getElementById('refresh-btn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', refreshAll);
    }
    
    // Clear events button
    const clearEventsBtn = document.getElementById('clear-events-btn');
    if (clearEventsBtn) {
        clearEventsBtn.addEventListener('click', clearEvents);
    }
    
    // Auto-refresh every 10 seconds
    setInterval(() => {
        loadStats();
        loadHealth();
    }, 10000);
});

// WebSocket event handlers
wsClient.on('connected', () => {
    addEvent('info', 'WebSocket connected');
});

wsClient.on('disconnected', () => {
    addEvent('warning', 'WebSocket disconnected');
});

wsClient.on('agent.started', (event) => {
    addEvent('success', `Agent ${event.data.agentId} started`);
    loadAgents();
});

wsClient.on('agent.stopped', (event) => {
    addEvent('info', `Agent ${event.data.agentId} stopped`);
    loadAgents();
});

wsClient.on('message.sent', (event) => {
    addEvent('info', `Message sent: ${event.data.topic}`);
});

wsClient.on('error.occurred', (event) => {
    addEvent('error', `Error: ${event.data.message}`);
});

wsClient.on('behavior.executed', (event) => {
    const { agentId, behaviorId, durationMs, success, error } = event.data;
    if (success) {
        addEvent('info', `[${agentId}] ${behaviorId} completed in ${durationMs}ms`);
    } else {
        addEvent('error', `[${agentId}] ${behaviorId} failed: ${error}`);
    }
});