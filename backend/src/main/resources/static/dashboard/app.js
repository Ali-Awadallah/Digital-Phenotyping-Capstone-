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
        alertsLiveInterval: null,
        loadingAlerts: false,
        alertRefreshTimer: null,
        alerts: [],
        participants: [],
        socket: null,
        participantMaps: {}
    };

    // API Configuration
    const API_BASE = '/api';
    const DASHBOARD_REFRESH_MS = 30000;
    const ALERTS_LIVE_REFRESH_MS = 5000;

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
            alertFilter.addEventListener('change', () => loadAlertsList());
        }

        const alertSourceFilter = document.getElementById('alert-source-filter');
        if (alertSourceFilter) {
            alertSourceFilter.addEventListener('change', () => loadAlertsList());
        }

        const alertParticipantFilter = document.getElementById('alert-participant-filter');
        if (alertParticipantFilter) {
            alertParticipantFilter.addEventListener('change', () => loadAlertsList());
        }

        const alertLimitFilter = document.getElementById('alert-limit-filter');
        if (alertLimitFilter) {
            alertLimitFilter.addEventListener('change', () => loadAlertsList());
        }

        // Refresh buttons
        const refreshMapBtn = document.getElementById('refresh-map-btn');
        if (refreshMapBtn) {
            refreshMapBtn.addEventListener('click', () => loadDashboardData());
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
            state.refreshInterval = null;
        }
        if (state.alertsLiveInterval) {
            clearInterval(state.alertsLiveInterval);
            state.alertsLiveInterval = null;
        }
        if (state.alertRefreshTimer) {
            clearTimeout(state.alertRefreshTimer);
            state.alertRefreshTimer = null;
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

        // Initialize WebSocket for real-time updates
        if (!state.socket) {
            initWebSocket();
        }

        // Start auto-refresh every 30 seconds
        state.refreshInterval = setInterval(() => {
            if (state.currentView === 'dashboard') {
                loadDashboardData();
            } else if (state.currentView === 'alerts') {
                loadAlertsList();
            }
        }, DASHBOARD_REFRESH_MS);
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

        // Keep alert list live while user is on Alerts view.
        if (viewName === 'alerts') {
            startAlertsLiveRefresh();
        } else {
            stopAlertsLiveRefresh();
        }
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

    function safeJsonParse(v) {
        if (!v) return null;
        if (typeof v === 'object') return v;
        try { return JSON.parse(v); } catch (e) { return null; }
    }



    // ---- DATA LOADING FUNCTIONS ----

    async function loadDashboardData() {
        // Load participants
        const participants = await apiGet('/participants');
        state.participants = participants || [];

        // Load alerts
        const geofenceAlerts = await apiGet('/alerts?active=true');
        const signatureAlerts = await apiGet('/signature-alerts?active=true&limit=10000');

        const merged = [
            ...(geofenceAlerts || []).map(a => ({ ...a, alert_type: 'geofence', source_type: 'phone' })),
            ...(signatureAlerts || []).map(a => ({ ...a, alert_type: 'signature', source_type: getAlertSource(a) }))
        ];

        const sortTs = (a) => a.triggered_at || a.created_at || a.hour_start || 0;
        merged.sort((a, b) => new Date(sortTs(b)).getTime() - new Date(sortTs(a)).getTime());
        state.alerts = merged;

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

        updateAlertParticipantFilter();

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

        const recentAlerts = state.alerts.slice(0, 5);

        if (recentAlerts.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; color:#888;">No alerts</td></tr>';
            return;
        }

        tbody.innerHTML = recentAlerts.map(alert => {
            const isGeofence = alert.alert_type === 'geofence';
            const isSignature = alert.alert_type === 'signature';

            if (isGeofence) {
                return `
                    <tr>
                        <td>${formatTimestamp(alert.triggered_at)}</td>
                        <td>${alert.participant_name || alert.participant_id || '--'}</td>
                        <td>Entered: ${alert.zone_name || 'Red Zone'}</td>
                        <td><span class="risk-badge risk-high">${alert.alert_type}</span></td>
                    </tr>
                `;
            } else if (isSignature) {
                return `
                    <tr>
                        <td>${formatTimestamp(alert.created_at || alert.hour_start, true)}</td>
                        <td>${alert.participant_id || '--'}</td>
                        <td>Signature: ${alert.alert_code || 'ALERT'} (${alert.severity || 'high'})</td>
                        <td><span class="risk-badge risk-high">signature</span></td>
                    </tr>
                `;
            }
        }).join('');
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

        if (location && location.latitude != null && location.longitude != null) {
            const lat = location.latitude;
            const lon = location.longitude;

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
                const ts = location.timestamp < 10000000000 ? location.timestamp * 1000 : location.timestamp;
                timestamp = new Date(ts).toLocaleString();
            }
            infoDiv.innerHTML = `
                <strong>Coordinates:</strong> ${lat.toFixed(6)}, ${lon.toFixed(6)} | 
                <strong>Accuracy:</strong> ${location.accuracy ? Math.round(location.accuracy) + 'm' : 'N/A'} | 
                <strong>Last Update:</strong> ${timestamp}
            `;
        } else {
            infoDiv.innerHTML = '<span style="color:#888;">No location data available for this participant</span>';
        }

        // Invalidate map size (needed when map is initially hidden)
        setTimeout(() => mapObj.map.invalidateSize(), 100);
    };

    // ---- ALERTS VIEW ----

    async function loadAlertsList(filter = 'all') {
        if (state.loadingAlerts) return;
        state.loadingAlerts = true;
        const container = document.getElementById('alerts-list');
        if (!container) {
            state.loadingAlerts = false;
            return;
        }
        if (!state.participants || state.participants.length === 0) {
            const participants = await apiGet('/participants');
            state.participants = participants || [];
        }

        const filterSelect = document.getElementById('alert-filter');
        filter = filterSelect ? filterSelect.value : filter;
        const sourceFilterSelect = document.getElementById('alert-source-filter');
        const sourceFilter = sourceFilterSelect ? sourceFilterSelect.value : 'all';
        const participantFilterSelect = document.getElementById('alert-participant-filter');
        const participantFilter = participantFilterSelect ? participantFilterSelect.value : 'all';
        const limitFilterSelect = document.getElementById('alert-limit-filter');
        const alertLimit = limitFilterSelect ? limitFilterSelect.value : '10000';

        let endpoint = '/alerts';
        if (filter === 'active') {
            endpoint = '/alerts?active=true';
        }

        const geofenceAlerts = await apiGet(endpoint);
        const sigEndpoint = (filter === 'active')
            ? `/signature-alerts?active=true&limit=${encodeURIComponent(alertLimit)}`
            : `/signature-alerts?limit=${encodeURIComponent(alertLimit)}`;
        const signatureAlerts = await apiGet(sigEndpoint);

        let alerts = [
            ...(geofenceAlerts || []).map(a => ({ ...a, alert_type: 'geofence', source_type: 'phone' })),
            ...(signatureAlerts || []).map(a => ({ ...a, alert_type: 'signature', source_type: getAlertSource(a) }))
        ];

        // Determine acknowledged status consistently
        const isAck = (a) => {
            if (a.alert_type === 'signature') return !!a.acknowledged_at;
            return !!a.acknowledged; // geofence
        };

        if (filter === 'acknowledged') alerts = alerts.filter(isAck);
        if (filter === 'active') alerts = alerts.filter(a => !isAck(a));

        // Sort all alerts by timestamp
        const sortTs = (a) => a.triggered_at || a.created_at || a.hour_start || 0;
        alerts.sort((a, b) => new Date(sortTs(b)).getTime() - new Date(sortTs(a)).getTime());

        // Filter locally for acknowledged (again if needed)
        let filteredAlerts = alerts;
        if (filter === 'acknowledged') {
            filteredAlerts = alerts.filter(isAck);
        } else if (filter === 'active') {
            filteredAlerts = alerts.filter(a => !isAck(a));
        }
        if (sourceFilter !== 'all') {
            filteredAlerts = filteredAlerts.filter(a => (a.source_type || getAlertSource(a)) === sourceFilter);
        }
        if (participantFilter !== 'all') {
            filteredAlerts = filteredAlerts.filter(a => (a.participant_id || a.participant_name || 'Unknown Participant') === participantFilter);
        }

        const groupedAlerts = new Map();
        const participantIds = new Set();
        const includeEmptyParticipants = participantFilter === 'all';

        if (includeEmptyParticipants) {
            (state.participants || []).forEach(participant => {
                const participantId = participant.participant_id || participant.device_id;
                if (!participantId) return;
                participantIds.add(participantId);
                groupedAlerts.set(participantId, []);
            });
        }

        filteredAlerts.forEach(alert => {
            const participantId = alert.participant_id || alert.participant_name || 'Unknown Participant';
            participantIds.add(participantId);
            if (!groupedAlerts.has(participantId)) {
                groupedAlerts.set(participantId, []);
            }
            groupedAlerts.get(participantId).push(alert);
        });

        const participantGroups = Array.from(participantIds).map(participantId => {
            const participantAlerts = groupedAlerts.get(participantId) || [];
            const latestTs = participantAlerts.length > 0 ? sortTs(participantAlerts[0]) : 0;
            return [participantId, participantAlerts, latestTs];
        }).sort((a, b) => {
            if ((b[1] || []).length !== (a[1] || []).length) {
                return (b[1] || []).length - (a[1] || []).length;
            }
            return new Date(b[2] || 0).getTime() - new Date(a[2] || 0).getTime();
        });

        if (participantGroups.length === 0) {
            container.innerHTML = '<div class="alert-item info"><div class="alert-content"><div class="alert-title">No Participants</div><div class="alert-description">No participants are available yet.</div></div></div>';
            state.loadingAlerts = false;
            return;
        }

        container.innerHTML = participantGroups.map(([participantId, participantAlerts]) => {
            const phoneCount = participantAlerts.filter(alert => (alert.source_type || getAlertSource(alert)) === 'phone').length;
            const watchCount = participantAlerts.filter(alert => (alert.source_type || getAlertSource(alert)) === 'watch').length;
            const participantBody = participantAlerts.map(alert => {
                const isGeofence = alert.alert_type === 'geofence';
                const isSignature = alert.alert_type === 'signature';
                const sourceType = alert.source_type || getAlertSource(alert);

                const title = isGeofence
                    ? `Geofence Breach: ${alert.zone_name || 'Red Zone'}`
                    : `Signature Alert: ${alert.alert_code || ''} (${alert.severity || 'high'})`;

                let desc;

                if (isGeofence) {
                    desc = `Participant ${alert.participant_name || alert.participant_id} entered a red zone. Distance from center: ${Math.round(alert.distance)}m`;
                } else {
                    const who = alert.participant_id || '--';
                    const name = alert.alert_name || '';
                    const expl = alert.explanation || '';
                    const score = alert.score ? `Score: ${alert.score.toFixed(4)}` : '';
                    desc = `Participant ${who}. ${name}${expl ? ' — ' + expl : ''} ${score}`;
                }

                const acknowledged = isSignature ? !!alert.acknowledged_at : !!alert.acknowledged;
                const ackText = acknowledged ? `| Acknowledged by ${alert.acknowledged_by || ''}` : '';

                const ackBtn = (!acknowledged)
                    ? (isGeofence
                        ? `<button class="btn btn-primary alert-ack-btn" onclick="acknowledgeAlert('${alert.alert_id}')"><i class="fas fa-check"></i><span>Acknowledge</span></button>`
                        : `<button class="btn btn-primary alert-ack-btn" onclick="acknowledgeSignatureAlert(${alert.id})"><i class="fas fa-check"></i><span>Acknowledge</span></button>`
                    )
                    : '';

                const shownTime = isSignature
                    ? (alert.created_at || alert.hour_start)
                    : alert.triggered_at;

                return `
                <div class="alert-item ${acknowledged ? 'info' : 'critical'}">
                    <div class="alert-icon">${acknowledged ? '<i class="fas fa-check"></i>' : '<i class="fas fa-exclamation"></i>'}</div>
                    <div class="alert-content">
                        <div class="alert-title">${title}</div>
                        <div class="alert-description">${desc}</div>
                        <div class="alert-meta">
                            <span class="alert-source-badge ${sourceType}">${sourceType === 'watch' ? 'Watch' : 'Phone'}</span>
                            ${formatTimestamp(shownTime, isSignature)} ${acknowledged ? ackText : ''}
                        </div>
                    </div>
                    <div class="alert-actions">
                        ${ackBtn}
                    </div>
                </div>
            `;
            }).join('') || `
                <div class="participant-no-alerts">
                    No ${sourceFilter === 'all' ? '' : sourceFilter + ' '}alerts for this participant in the current filter.
                </div>
            `;

            return `
                <div class="participant-alert-group">
                    <div class="participant-alert-header">
                        <div class="participant-alert-title">${participantId}</div>
                        <div class="participant-alert-count">
                            ${participantAlerts.length} alert${participantAlerts.length === 1 ? '' : 's'}
                            <span class="participant-alert-breakdown">Phone: ${phoneCount} | Watch: ${watchCount}</span>
                        </div>
                    </div>
                    <div class="participant-alert-body">
                        ${participantBody}
                    </div>
                </div>
            `;
        }).join('');
        state.loadingAlerts = false;
    }

    function startAlertsLiveRefresh() {
        if (state.alertsLiveInterval) return;
        state.alertsLiveInterval = setInterval(() => {
            if (state.currentView === 'alerts') {
                loadAlertsList();
            }
        }, ALERTS_LIVE_REFRESH_MS);
    }

    function stopAlertsLiveRefresh() {
        if (!state.alertsLiveInterval) return;
        clearInterval(state.alertsLiveInterval);
        state.alertsLiveInterval = null;
    }

    function getAlertSource(alert) {
        if (alert.alert_type === 'geofence') return 'phone';
        const code = (alert.alert_code || '').toUpperCase();
        if (code.startsWith('W')) return 'watch';
        return 'phone';
    }

    function updateAlertParticipantFilter() {
        const participantFilter = document.getElementById('alert-participant-filter');
        if (!participantFilter) return;

        const previousValue = participantFilter.value || 'all';
        const participantIds = Array.from(
            new Set(
                (state.participants || [])
                    .map(participant => participant.participant_id || participant.device_id)
                    .filter(Boolean)
            )
        ).sort();

        participantFilter.innerHTML = [
            '<option value="all">All Participants</option>',
            ...participantIds.map(participantId => `<option value="${participantId}">${participantId}</option>`)
        ].join('');

        if (participantIds.includes(previousValue)) {
            participantFilter.value = previousValue;
        } else {
            participantFilter.value = 'all';
        }
    }

    // Global function for acknowledge geofence alert button
    window.acknowledgeAlert = async function (alertId) {
        const username = sessionStorage.getItem('dp_ids_user') || 'admin';
        const result = await apiPost(`/alerts/${alertId}/acknowledge?by=${username}`, {});
        if (result && result.ok) {
            loadAlertsList();
            loadDashboardData(); // Refresh dashboard stats too
        }
    };



    // Global function for acknowledge SIGNATURE alert button
    window.acknowledgeSignatureAlert = async function (id) {
        const username = sessionStorage.getItem('dp_ids_user') || 'admin';
        const result = await apiPost(`/signature-alerts/${id}/acknowledge?by=${username}`, {});
        if (result && result.ok) {
            loadAlertsList();
            loadDashboardData();
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
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:#888;">No devices registered</td></tr>';
            return;
        }

        tbody.innerHTML = participants.map(p => {
            const batteryStr = p.percentage !== null && p.percentage !== undefined
                ? `${Math.round(p.percentage)}% (${p.charging_status || 'unknown'})`
                : '--';
            const sourceType = (p.source_type || 'unknown').toLowerCase();
            const sourceLabel = sourceType === 'both'
                ? 'Phone + Watch'
                : (sourceType === 'watch' ? 'Watch' : (sourceType === 'phone' ? 'Phone' : 'Unknown'));
            const sourceBadgeClass = sourceType === 'both'
                ? 'both'
                : (sourceType === 'watch' ? 'watch' : 'phone');

            return `
                <tr>
                    <td><code>${p.device_id}</code></td>
                    <td>${p.name}</td>
                    <td><span class="alert-source-badge ${sourceBadgeClass}">${sourceLabel}</span></td>
                    <td>${formatTimestamp(p.updated_at) || 'Unknown'}</td>
                    <td id="battery-${p.device_id}">${batteryStr}</td>
                    <td>8</td>
                    <td><span class="status-badge status-${p.status === 'active' ? 'active' : 'inactive'}">${p.status}</span></td>
                </tr>
            `;
        }).join('');
    }

    function initWebSocket() {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${location.hostname}:8081`;

        console.log('Connecting to WebSocket at', wsUrl);
        state.socket = new WebSocket(wsUrl);

        state.socket.onopen = () => {
            console.log('Connected to WebSocket server');
            const indicator = document.querySelector('.status-indicator');
            if (indicator) {
                indicator.style.backgroundColor = '#2ecc71'; // Green
            }
        };

        state.socket.onmessage = (event) => {
            try {
                const message = JSON.parse(event.data);
                if (message.type === 'battery_update') {
                    updateBatteryUI(message.data);
                } else if (message.type === 'alert_update') {
                    scheduleAlertRefresh();
                }
            } catch (e) {
                console.error('Error parsing WebSocket message:', e);
            }
        };

        state.socket.onclose = () => {
            console.log('WebSocket connection closed, retrying in 5 seconds...');
            state.socket = null;
            setTimeout(initWebSocket, 5000);
        };

        state.socket.onerror = (error) => {
            console.error('WebSocket error:', error);
        };
    }

    function updateBatteryUI(data) {
        const el = document.getElementById(`battery-${data.device_id}`);
        if (el) {
            const batteryStr = `${Math.round(data.percentage)}% (${data.charging_status || 'unknown'})`;
            el.textContent = batteryStr;

            // Brief highlight effect
            el.style.color = '#3498db';
            el.style.fontWeight = 'bold';
            setTimeout(() => {
                el.style.color = '';
                el.style.fontWeight = '';
            }, 3000);
        }
    }

    function scheduleAlertRefresh() {
        if (state.alertRefreshTimer) return;
        state.alertRefreshTimer = setTimeout(async () => {
            state.alertRefreshTimer = null;
            if (!state.isAuthenticated) return;
            await loadDashboardData();
            if (state.currentView === 'alerts') {
                await loadAlertsList();
            }
        }, 400);
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

    function formatTimestamp(timestamp, isSignature = false) {
        if (!timestamp) return 'Unknown';
        let ts = timestamp;
        if (typeof ts === 'string') {
            if (!ts.includes('T')) {
                ts = ts.replace(' ', 'T');
            }
            if (isSignature) {
                if (!ts.endsWith('Z') && !/[+-]\d{2}:\d{2}$/.test(ts)) {
                    ts = ts + 'Z';
                }
            } else {
                ts = ts.replace('Z', '');
            }
        }
        const date = new Date(ts);
        if (isNaN(date.getTime())) return String(timestamp);
        return date.toLocaleString('en-US', {
            year: 'numeric',
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
