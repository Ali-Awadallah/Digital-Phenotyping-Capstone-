(function () {
    'use strict';

    // Application State
    const state = {
        currentView: 'dashboard',
        isAuthenticated: false,
        lastSync: new Date(),
        map: null,
        markers: [],
        refreshInterval: null,
        alerts: [],
        participants: []
    };

    // API Configuration
    const API_BASE = '/api';

    // DOM Elements
    const elements = {
        loginScreen: document.getElementById('login-screen'),
        loginForm: document.getElementById('login-form'),
        dashboard: document.getElementById('dashboard'),
        logoutBtn: document.getElementById('logout-btn'),
        pageTitle: document.getElementById('page-title'),
        navItems: document.querySelectorAll('.nav-item'),
        views: document.querySelectorAll('.view')
    };

    // Initialize Application
    function init() {
        bindEvents();
        checkAuthState();
    }

    // Bind Event Listeners
    function bindEvents() {
        // Login form submission
        elements.loginForm.addEventListener('submit', handleLogin);

        // Logout button
        elements.logoutBtn.addEventListener('click', handleLogout);

        // Navigation items
        elements.navItems.forEach(item => {
            item.addEventListener('click', (e) => {
                e.preventDefault();
                const view = item.getAttribute('data-view');
                navigateTo(view);
            });
        });

        // Alert filter
        const alertFilter = document.getElementById('alert-filter');
        if (alertFilter) {
            alertFilter.addEventListener('change', filterAlerts);
        }

        // Refresh buttons
        const refreshMapBtn = document.getElementById('refresh-map-btn');
        if (refreshMapBtn) {
            refreshMapBtn.addEventListener('click', refreshMapData);
        }

        const refreshAlertsBtn = document.getElementById('refresh-alerts-btn');
        if (refreshAlertsBtn) {
            refreshAlertsBtn.addEventListener('click', loadAlertsList);
        }
    }

    // Check Authentication State
    function checkAuthState() {
        const isAuth = sessionStorage.getItem('dp_ids_auth');
        if (isAuth === 'true') {
            showDashboard();
        } else {
            showLogin();
        }
    }

    // Handle Login
    function handleLogin(e) {
        e.preventDefault();
        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;

        // Demo authentication - accept any non-empty credentials
        if (username && password) {
            sessionStorage.setItem('dp_ids_auth', 'true');
            sessionStorage.setItem('dp_ids_user', username);
            showDashboard();
        }
    }

    // Handle Logout
    function handleLogout() {
        sessionStorage.removeItem('dp_ids_auth');
        sessionStorage.removeItem('dp_ids_user');
        if (state.refreshInterval) {
            clearInterval(state.refreshInterval);
        }
        showLogin();
    }

    // Show Login Screen
    function showLogin() {
        elements.loginScreen.classList.remove('hidden');
        elements.dashboard.classList.add('hidden');
        state.isAuthenticated = false;
    }

    // Show Dashboard
    function showDashboard() {
        elements.loginScreen.classList.add('hidden');
        elements.dashboard.classList.remove('hidden');
        state.isAuthenticated = true;

        // Update current user display
        const username = sessionStorage.getItem('dp_ids_user') || 'Admin';
        document.querySelector('.current-user').textContent = username;

        // Load initial data
        loadDashboardData();
        navigateTo('dashboard');

        // Start auto-refresh every 30 seconds
        state.refreshInterval = setInterval(() => {
            if (state.currentView === 'dashboard') {
                loadDashboardData();
            } else if (state.currentView === 'alerts') {
                loadAlertsList();
            }
        }, 30000);
    }

    // Navigate to View
    function navigateTo(viewName) {
        state.currentView = viewName;

        // Update navigation active state
        elements.navItems.forEach(item => {
            item.classList.remove('active');
            if (item.getAttribute('data-view') === viewName) {
                item.classList.add('active');
            }
        });

        // Show selected view
        elements.views.forEach(view => {
            view.classList.remove('active');
        });
        const targetView = document.getElementById('view-' + viewName);
        if (targetView) {
            targetView.classList.add('active');
        }

        // Update page title
        const titles = {
            dashboard: 'Dashboard',
            participants: 'Participant Management',
            alerts: 'Alert Center',
            devices: 'Device Management',
            users: 'User Administration',
            settings: 'System Settings'
        };
        elements.pageTitle.textContent = titles[viewName] || 'Dashboard';

        // Load view-specific data
        loadViewData(viewName);
    }

    // ---- API FUNCTIONS ----

    async function apiGet(endpoint) {
        try {
            const response = await fetch(`${API_BASE}${endpoint}`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return await response.json();
        } catch (error) {
            console.error(`API GET ${endpoint} failed:`, error);
            return null;
        }
    }

    async function apiPost(endpoint, data) {
        try {
            const response = await fetch(`${API_BASE}${endpoint}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return await response.json();
        } catch (error) {
            console.error(`API POST ${endpoint} failed:`, error);
            return null;
        }
    }

    // ---- DATA LOADING FUNCTIONS ----

    async function loadDashboardData() {
        // Load participants count
        const participants = await apiGet('/participants');
        const alerts = await apiGet('/alerts?active=true');

        state.participants = participants || [];
        state.alerts = alerts || [];

        // Update stats
        document.getElementById('stat-participants').textContent = state.participants.length;
        document.getElementById('stat-devices').textContent = state.participants.length;
        document.getElementById('stat-alerts').textContent = state.alerts.length;

        // Update alert badge in sidebar
        const alertBadge = document.getElementById('alert-count');
        if (alertBadge) {
            alertBadge.textContent = state.alerts.length;
        }

        // Update last sync time
        state.lastSync = new Date();
        document.getElementById('last-sync').textContent = formatTime(state.lastSync);

        // Load recent alerts
        loadRecentAlerts();
    }

    function loadViewData(viewName) {
        switch (viewName) {
            case 'participants':
                loadParticipantsView();
                break;
            case 'alerts':
                loadAlertsList();
                break;
            case 'devices':
                loadDevicesTable();
                break;
            case 'users':
                loadUsersTable();
                break;
        }
    }

    // ---- RECENT ALERTS (Dashboard) ----

    function loadRecentAlerts() {
        const tbody = document.getElementById('recent-alerts-table');
        if (!tbody) return;

        if (state.alerts.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; color:#888;">No active alerts</td></tr>';
            return;
        }

        const recentAlerts = state.alerts.slice(0, 5);
        tbody.innerHTML = recentAlerts.map(alert => `
            <tr>
                <td>${formatTimestamp(alert.triggered_at)}</td>
                <td>${alert.participant_name || alert.participant_id}</td>
                <td>Entered: ${alert.zone_name || 'Red Zone'}</td>
                <td><span class="risk-badge risk-high">geofence</span></td>
            </tr>
        `).join('');
    }

    // ---- PARTICIPANTS VIEW ----

    async function loadParticipantsView() {
        await loadParticipantsTable();
    }

    async function loadParticipantsTable() {
        const tbody = document.getElementById('participants-table');
        if (!tbody) return;

        const participants = await apiGet('/participants');
        state.participants = participants || [];

        if (state.participants.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:#888;">No participants registered. Devices will auto-register when sending data.</td></tr>';
            return;
        }

        tbody.innerHTML = state.participants.map(p => `
            <tr id="row-${p.participant_id}">
                <td>${p.participant_id.substring(0, 8)}...</td>
                <td>${p.name}</td>
                <td><code>${p.device_id}</code></td>
                <td><span class="status-badge status-${p.status}">${p.status}</span></td>
                <td><span class="risk-badge risk-${p.risk_level}">${p.risk_level}</span></td>
                <td>
                    <button class="btn btn-sm btn-info" id="loc-btn-${p.participant_id}" onclick="toggleLocationMap('${p.participant_id}', '${p.device_id}', '${p.name}')"><i style="margin-right: 5px;" class="fas fa-map-marker-alt"></i> Location</button>
                    <button class="btn btn-sm btn-secondary" onclick="openParticipantModal('${p.participant_id}')"><i style="margin-right: 5px;" class="fas fa-cog"></i> Settings</button>
                    <button class="btn btn-sm btn-primary" onclick="openZonesModal('${p.participant_id}')"><i style="margin-right: 5px;" class="fas fa-map-marked-alt"></i> Red Zones</button>
                </td>
            </tr>
            <tr id="map-row-${p.participant_id}" class="map-row hidden">
                <td colspan="7">
                    <div class="participant-map-container">
                        <div class="map-header">
                            <span>Live Location: ${p.name}</span>
                            <button class="btn btn-sm btn-secondary" onclick="refreshParticipantMap('${p.participant_id}', '${p.device_id}')"><i style="margin-right: 5px;" class="fas fa-sync-alt"></i> Refresh</button>
                        </div>
                        <div id="map-${p.participant_id}" class="participant-map"></div>
                        <div id="map-info-${p.participant_id}" class="map-info"></div>
                    </div>
                </td>
            </tr>
        `).join('');
    }

    // ---- INDIVIDUAL PARTICIPANT MAP FUNCTIONALITY ----

    // Store individual maps
    state.participantMaps = {};

    window.toggleLocationMap = async function (participantId, deviceId, name) {
        const mapRow = document.getElementById(`map-row-${participantId}`);
        const btn = document.getElementById(`loc-btn-${participantId}`);

        if (mapRow.classList.contains('hidden')) {
            // Show map
            mapRow.classList.remove('hidden');
            btn.innerHTML = '<i style="margin-right: 5px;" class="fas fa-eye-slash"></i> Hide';

            // Initialize map if not already done
            if (!state.participantMaps[participantId]) {
                const mapContainer = document.getElementById(`map-${participantId}`);
                const map = L.map(mapContainer).setView([25.2867, 51.5333], 12);
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    attribution: '© OpenStreetMap'
                }).addTo(map);
                state.participantMaps[participantId] = { map, marker: null };
            }

            // Load location data
            await refreshParticipantMap(participantId, deviceId);
        } else {
            // Hide map
            mapRow.classList.add('hidden');
            btn.innerHTML = '<i class="fas fa-map-marker-alt"></i> Location';
        }
    };

    window.refreshParticipantMap = async function (participantId, deviceId) {
        const mapObj = state.participantMaps[participantId];
        if (!mapObj) return;

        const infoDiv = document.getElementById(`map-info-${participantId}`);
        infoDiv.innerHTML = '<span style="color:#888;">Loading location...</span>';

        const location = await apiGet(`/participants/${deviceId}/location`);

        if (location && location.data) {
            try {
                const data = typeof location.data === 'string' ? JSON.parse(location.data) : location.data;
                const lat = data.latitude;
                const lon = data.longitude;

                if (lat && lon) {
                    // Remove old marker
                    if (mapObj.marker) {
                        mapObj.map.removeLayer(mapObj.marker);
                    }

                    // Add new marker
                    mapObj.marker = L.marker([lat, lon]).addTo(mapObj.map);
                    mapObj.map.setView([lat, lon], 15);

                    // Update info - handle timestamp (could be in seconds or milliseconds)
                    let timestamp = 'Unknown';
                    if (location.timestamp) {
                        // If timestamp is less than 10 billion, it's in seconds; otherwise milliseconds
                        const ts = location.timestamp < 10000000000 ? location.timestamp * 1000 : location.timestamp;
                        timestamp = new Date(ts).toLocaleString();
                    }
                    infoDiv.innerHTML = `
                        <strong>Coordinates:</strong> ${lat.toFixed(6)}, ${lon.toFixed(6)} | 
                        <strong>Accuracy:</strong> ${data.accuracy ? Math.round(data.accuracy) + 'm' : 'N/A'} | 
                        <strong>Last Update:</strong> ${timestamp}
                    `;
                } else {
                    infoDiv.innerHTML = '<span style="color:#c9302c;">No valid coordinates available</span>';
                }
            } catch (e) {
                console.warn('Failed to parse location data:', e);
                infoDiv.innerHTML = '<span style="color:#c9302c;">Error parsing location data</span>';
            }
        } else {
            infoDiv.innerHTML = '<span style="color:#888;">No location data available for this participant</span>';
        }

        // Invalidate map size (needed when map is initially hidden)
        setTimeout(() => mapObj.map.invalidateSize(), 100);
    };

    // ---- ALERTS VIEW ----

    async function loadAlertsList(filter = 'all') {
        const container = document.getElementById('alerts-list');
        if (!container) return;

        const filterSelect = document.getElementById('alert-filter');
        filter = filterSelect ? filterSelect.value : filter;

        let endpoint = '/alerts';
        if (filter === 'active') {
            endpoint = '/alerts?active=true';
        }

        const alerts = await apiGet(endpoint);

        if (!alerts || alerts.length === 0) {
            container.innerHTML = '<div class="alert-item info"><div class="alert-content"><div class="alert-title">No Alerts</div><div class="alert-description">No geofence alerts have been triggered.</div></div></div>';
            return;
        }

        // Filter locally for acknowledged
        let filteredAlerts = alerts;
        if (filter === 'acknowledged') {
            filteredAlerts = alerts.filter(a => a.acknowledged);
        } else if (filter === 'active') {
            filteredAlerts = alerts.filter(a => !a.acknowledged);
        }

        container.innerHTML = filteredAlerts.map(alert => `
            <div class="alert-item ${alert.acknowledged ? 'info' : 'critical'}">
                <div class="alert-icon">${alert.acknowledged ? '<i class="fas fa-check"></i>' : '<i class="fas fa-exclamation"></i>'}</div>
                <div class="alert-content">
                    <div class="alert-title">Geofence Breach: ${alert.zone_name || 'Red Zone'}</div>
                    <div class="alert-description">
                        Participant ${alert.participant_name || alert.participant_id} entered a red zone.
                        Distance from center: ${Math.round(alert.distance)}m
                    </div>
                    <div class="alert-meta">
                        ${formatTimestamp(alert.triggered_at)}
                        ${alert.acknowledged ? `| Acknowledged by ${alert.acknowledged_by}` : ''}
                    </div>
                </div>
                <div class="alert-actions">
                    ${!alert.acknowledged ? `
                        <button class="btn btn-sm btn-primary" onclick="acknowledgeAlert('${alert.alert_id}')"><i style="margin-right: 5px;" class="fas fa-check"></i> Acknowledge</button>
                    ` : ''}
                </div>
            </div>
        `).join('');
    }

    function filterAlerts() {
        loadAlertsList();
    }

    // Global function for acknowledge button
    window.acknowledgeAlert = async function (alertId) {
        const username = sessionStorage.getItem('dp_ids_user') || 'admin';
        const result = await apiPost(`/alerts/${alertId}/acknowledge?by=${username}`, {});
        if (result && result.ok) {
            loadAlertsList();
            loadDashboardData(); // Refresh dashboard stats too
        }
    };

    // ---- PARTICIPANT MODAL FUNCTIONS ----

    window.openParticipantModal = function (participantId) {
        const participant = state.participants.find(p => p.participant_id === participantId);
        if (!participant) return;

        document.getElementById('modal-participant-id').value = participant.participant_id;
        document.getElementById('modal-participant-name').value = participant.name;
        document.getElementById('modal-device-id').value = participant.device_id;
        document.getElementById('modal-red-zone-radius').value = participant.red_zone_radius || 300;
        document.getElementById('modal-risk-level').value = participant.risk_level || 'low';
        document.getElementById('modal-status').value = participant.status || 'active';

        document.getElementById('participant-modal').classList.remove('hidden');
    };

    window.closeParticipantModal = function () {
        document.getElementById('participant-modal').classList.add('hidden');
    };

    window.saveParticipant = async function () {
        const participantId = document.getElementById('modal-participant-id').value;
        const data = {
            participant_id: participantId,
            device_id: document.getElementById('modal-device-id').value,
            name: document.getElementById('modal-participant-name').value,
            red_zone_radius: parseInt(document.getElementById('modal-red-zone-radius').value),
            risk_level: document.getElementById('modal-risk-level').value,
            status: document.getElementById('modal-status').value
        };

        const result = await apiPost('/participants', data);
        if (result) {
            closeParticipantModal();
            await loadParticipantsTable();
        }
    };

    // ---- RED ZONES MODAL FUNCTIONS ----

    window.openZonesModal = async function (participantId) {
        const participant = state.participants.find(p => p.participant_id === participantId);
        if (!participant) return;

        document.getElementById('zones-participant-id').value = participantId;
        document.getElementById('zones-participant-info').textContent = `Managing red zones for: ${participant.name}`;

        await loadZonesTable(participantId);

        document.getElementById('zones-modal').classList.remove('hidden');
    };

    window.closeZonesModal = function () {
        document.getElementById('zones-modal').classList.add('hidden');
    };

    async function loadZonesTable(participantId) {
        const tbody = document.getElementById('zones-table');
        if (!tbody) return;

        const zones = await apiGet(`/zones?participant_id=${participantId}`);

        if (!zones || zones.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:#888;">No red zones configured. Add one above.</td></tr>';
            return;
        }

        tbody.innerHTML = zones.map(zone => `
            <tr>
                <td>${zone.name}</td>
                <td>${zone.latitude.toFixed(6)}, ${zone.longitude.toFixed(6)}</td>
                <td>${zone.radius}m</td>
                <td><span class="risk-badge risk-${zone.zone_type === 'bar' || zone.zone_type === 'dealer' ? 'high' : 'moderate'}">${zone.zone_type}</span></td>
                <td>
                    <button class="btn btn-sm btn-danger" onclick="deleteRedZone('${zone.zone_id}')"><i style="margin-right: 5px;" class="fas fa-trash"></i> Delete</button>
                </td>
            </tr>
        `).join('');
    }

    window.addRedZone = async function () {
        const participantId = document.getElementById('zones-participant-id').value;
        const data = {
            participant_id: participantId,
            name: document.getElementById('new-zone-name').value,
            latitude: parseFloat(document.getElementById('new-zone-lat').value),
            longitude: parseFloat(document.getElementById('new-zone-lon').value),
            radius: parseInt(document.getElementById('new-zone-radius').value),
            zone_type: document.getElementById('new-zone-type').value
        };

        if (!data.name || !data.latitude || !data.longitude) {
            alert('Please fill in zone name, latitude, and longitude.');
            return;
        }

        const result = await apiPost('/zones', data);
        if (result) {
            // Clear form
            document.getElementById('new-zone-name').value = '';
            document.getElementById('new-zone-lat').value = '';
            document.getElementById('new-zone-lon').value = '';
            document.getElementById('new-zone-radius').value = '300';
            document.getElementById('new-zone-type').value = 'custom';

            await loadZonesTable(participantId);
        }
    };

    window.deleteRedZone = async function (zoneId) {
        if (!confirm('Are you sure you want to delete this red zone?')) return;

        const participantId = document.getElementById('zones-participant-id').value;
        const result = await fetch(`${API_BASE}/zones/${zoneId}`, { method: 'DELETE' });

        if (result.ok) {
            await loadZonesTable(participantId);
        }
    };

    // ---- DEVICES TABLE ----

    async function loadDevicesTable() {
        const tbody = document.getElementById('devices-table');
        if (!tbody) return;

        const participants = await apiGet('/participants') || [];

        if (participants.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color:#888;">No devices registered</td></tr>';
            return;
        }

        tbody.innerHTML = participants.map(p => `
            <tr>
                <td><code>${p.device_id}</code></td>
                <td>${p.name}</td>
                <td>${formatTimestamp(p.updated_at) || 'Unknown'}</td>
                <td>--</td>
                <td>6</td>
                <td><span class="status-badge status-${p.status === 'active' ? 'active' : 'inactive'}">${p.status}</span></td>
            </tr>
        `).join('');
    }

    // ---- USERS TABLE ----

    function loadUsersTable() {
        const tbody = document.getElementById('users-table');
        if (!tbody) return;

        const users = [
            { username: 'admin', role: 'Administrator', email: 'admin@clinic.org', lastLogin: 'Just now', status: 'active' }
        ];

        tbody.innerHTML = users.map(user => `
            <tr>
                <td>${user.username}</td>
                <td>${user.role}</td>
                <td>${user.email}</td>
                <td>${user.lastLogin}</td>
                <td><span class="status-badge status-${user.status}">${user.status}</span></td>
                <td>
                    <button class="btn btn-sm btn-secondary">Edit</button>
                </td>
            </tr>
        `).join('');
    }

    // ---- UTILITY FUNCTIONS ----

    function formatNumber(num) {
        return num.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    function formatTime(date) {
        return date.toLocaleTimeString('en-US', {
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    function formatTimestamp(timestamp) {
        if (!timestamp) return 'Unknown';
        const date = new Date(timestamp);
        return date.toLocaleString('en-US', {
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

})();
