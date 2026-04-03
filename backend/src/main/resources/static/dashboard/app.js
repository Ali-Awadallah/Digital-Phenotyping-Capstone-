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
        participantMaps: {},
        alertGroupExpanded: {}
    };

    // API Configuration
    const API_BASE = '/api';
    const ADMIN_API_KEY_STORAGE = 'dp_ids_admin_api_key';
    const AUTH_TOKEN_STORAGE = 'dp_ids_auth_token';
    const AUTH_ROLE_STORAGE = 'dp_ids_role';
    const ALERT_FILTER_STATE_STORAGE = 'dp_ids_alert_filter_state';
    const ALERT_PRESET_STORAGE = 'dp_ids_alert_filter_preset';
    const DASHBOARD_REFRESH_MS = 30000;
    const ALERTS_LIVE_REFRESH_MS = 5000;
    const ADMIN_ONLY_VIEWS = new Set(['users', 'settings']);
    const OPERATIONAL_VIEWS = new Set(['participants', 'alerts', 'devices', 'dashboard']);

    function getCurrentRole() {
        return (sessionStorage.getItem(AUTH_ROLE_STORAGE) || 'viewer').toLowerCase();
    }

    function isAdminRole() {
        return getCurrentRole() === 'admin';
    }

    function isDoctorRole() {
        return getCurrentRole() === 'doctor';
    }

    function canReadAdminData() {
        const role = getCurrentRole();
        return role === 'admin' || role === 'analyst' || role === 'viewer' || role === 'doctor';
    }

    function canReadParticipantsData() {
        return isDoctorRole() || canReadAdminData();
    }

    function canWriteAdminData() {
        const role = getCurrentRole();
        return role === 'admin' || role === 'analyst';
    }

    function canAccessView(viewName) {
        if (ADMIN_ONLY_VIEWS.has(viewName)) {
            return isAdminRole();
        }
        if (OPERATIONAL_VIEWS.has(viewName)) {
            return canReadAdminData();
        }
        return true;
    }

    function setElementVisible(elementId, visible) {
        const el = document.getElementById(elementId);
        if (!el) return;
        el.style.display = visible ? '' : 'none';
    }

    function setViewRoleHint(viewName, message) {
        const view = document.getElementById(`view-${viewName}`);
        if (!view) return;
        const header = view.querySelector('.view-header');
        if (!header) return;

        let hint = header.querySelector('.role-hint');
        if (!message) {
            if (hint) hint.remove();
            return;
        }
        if (!hint) {
            hint = document.createElement('div');
            hint.className = 'role-hint';
            header.appendChild(hint);
        }
        hint.textContent = message;
    }

    function applyRolePermissions() {
        const role = getCurrentRole();
        const adminOnly = role === 'admin';
        const canWrite = canWriteAdminData();
        const canRead = canReadAdminData();
        const canReadParticipants = canReadParticipantsData();
        const dashboardNav = document.querySelector('.nav-item[data-view="dashboard"]');
        if (dashboardNav) {
            dashboardNav.style.display = canRead ? '' : 'none';
        }

        const usersNav = document.querySelector('.nav-item[data-view="users"]');
        if (usersNav) {
            usersNav.style.display = adminOnly ? '' : 'none';
        }
        const settingsNav = document.querySelector('.nav-item[data-view="settings"]');
        if (settingsNav) {
            settingsNav.style.display = adminOnly ? '' : 'none';
        }

        const participantsNav = document.querySelector('.nav-item[data-view="participants"]');
        if (participantsNav) participantsNav.style.display = canReadParticipants ? '' : 'none';
        const alertsNav = document.querySelector('.nav-item[data-view="alerts"]');
        if (alertsNav) alertsNav.style.display = canRead ? '' : 'none';
        const devicesNav = document.querySelector('.nav-item[data-view="devices"]');
        if (devicesNav) devicesNav.style.display = canRead ? '' : 'none';

        setElementVisible('add-user-btn', adminOnly);
        setElementVisible('refresh-admin-security-btn', adminOnly);
        setElementVisible('add-participant-btn', canWrite);

        setViewRoleHint('participants', canWrite ? '' : 'Read-only mode: participants can be viewed but not edited.');
        setViewRoleHint('alerts', canWrite ? '' : 'Read-only mode: alert acknowledgment is disabled.');
    }

    function getAlertFilterElements() {
        return {
            status: document.getElementById('alert-filter'),
            source: document.getElementById('alert-source-filter'),
            participant: document.getElementById('alert-participant-filter'),
            limit: document.getElementById('alert-limit-filter')
        };
    }

    function getCurrentAlertFilterState() {
        const filters = getAlertFilterElements();
        return {
            status: filters.status ? filters.status.value : 'all',
            source: filters.source ? filters.source.value : 'all',
            participant: filters.participant ? filters.participant.value : 'all',
            limit: filters.limit ? filters.limit.value : '10000'
        };
    }

    function applyAlertFilterState(filterState) {
        if (!filterState || typeof filterState !== 'object') return;
        const filters = getAlertFilterElements();
        if (filters.status && filterState.status) filters.status.value = filterState.status;
        if (filters.source && filterState.source) filters.source.value = filterState.source;
        if (filters.participant && filterState.participant) filters.participant.value = filterState.participant;
        if (filters.limit && filterState.limit) filters.limit.value = filterState.limit;
    }

    function persistAlertFilterState(filterState, preset = 'custom') {
        try {
            localStorage.setItem(ALERT_FILTER_STATE_STORAGE, JSON.stringify(filterState || getCurrentAlertFilterState()));
            localStorage.setItem(ALERT_PRESET_STORAGE, preset);
        } catch (e) {
            console.warn('Failed to persist alert filters:', e);
        }
    }

    function restoreAlertFilterState() {
        try {
            const raw = localStorage.getItem(ALERT_FILTER_STATE_STORAGE);
            if (!raw) return;
            const parsed = JSON.parse(raw);
            applyAlertFilterState(parsed);
        } catch (e) {
            console.warn('Failed to restore alert filters:', e);
        }
    }

    function getCurrentPresetId() {
        return localStorage.getItem(ALERT_PRESET_STORAGE) || 'custom';
    }

    function refreshAlertPresetButtons(activePreset = getCurrentPresetId()) {
        document.querySelectorAll('.alert-preset-btn').forEach(btn => {
            const presetId = btn.getAttribute('data-preset');
            btn.classList.toggle('active', presetId === activePreset);
        });
    }

    function applyAlertPreset(presetId) {
        const presets = {
            all: { status: 'all', source: 'all', participant: 'all', limit: '10000' },
            phone_active: { status: 'active', source: 'phone', participant: 'all', limit: '200' },
            watch_active: { status: 'active', source: 'watch', participant: 'all', limit: '200' },
            acknowledged: { status: 'acknowledged', source: 'all', participant: 'all', limit: '1000' }
        };
        const preset = presets[presetId];
        if (!preset) return;

        applyAlertFilterState(preset);
        persistAlertFilterState(getCurrentAlertFilterState(), presetId);
        refreshAlertPresetButtons(presetId);
        loadAlertsList();
    }

    function participantGroupDomId(participantId) {
        return encodeURIComponent(String(participantId)).replace(/%/g, '_');
    }

    function alertTimestampValue(alert) {
        return alert?.triggered_at || alert?.created_at || alert?.hour_start || 0;
    }

    function buildAlertDensitySparkline(alerts, bucketCount = 10) {
        const normalizedBuckets = Array(Math.max(4, bucketCount)).fill(0);
        if (!Array.isArray(alerts) || alerts.length === 0) {
            return {
                barsHtml: normalizedBuckets.map(() => '<span class="spark-bar empty"></span>').join(''),
                total: 0
            };
        }

        const end = new Date();
        end.setHours(0, 0, 0, 0);

        alerts.forEach(alert => {
            const ts = alertTimestampValue(alert);
            const d = new Date(ts);
            if (isNaN(d.getTime())) return;
            d.setHours(0, 0, 0, 0);
            const deltaDays = Math.floor((end.getTime() - d.getTime()) / 86400000);
            if (deltaDays >= 0 && deltaDays < normalizedBuckets.length) {
                const bucketIndex = normalizedBuckets.length - 1 - deltaDays;
                normalizedBuckets[bucketIndex] += 1;
            }
        });

        const max = Math.max(...normalizedBuckets, 1);
        const barsHtml = normalizedBuckets.map((count, index) => {
            const pct = count === 0 ? 12 : Math.max(24, Math.round((count / max) * 100));
            const cls = count === 0 ? 'spark-bar empty' : 'spark-bar';
            return `<span class="${cls}" style="height:${pct}%;" title="Window ${index + 1}: ${count} alert(s)"></span>`;
        }).join('');

        return {
            barsHtml,
            total: normalizedBuckets.reduce((a, b) => a + b, 0)
        };
    }

    function humanizeFeatureName(name) {
        return String(name || '')
            .replace(/_/g, ' ')
            .replace(/\s+/g, ' ')
            .trim()
            .replace(/\b\w/g, c => c.toUpperCase());
    }

    function formatFeatureValue(value) {
        if (value === null || value === undefined) return '--';
        if (typeof value === 'number') {
            if (!Number.isFinite(value)) return '--';
            if (Math.abs(value) >= 1000) return value.toFixed(0);
            if (Math.abs(value) >= 100) return value.toFixed(1);
            if (Math.abs(value) >= 10) return value.toFixed(2);
            return value.toFixed(3);
        }
        return String(value);
    }

    function buildBaselineDiffHtml(alert) {
        if (!alert || alert.alert_type !== 'signature') return '';
        const features = safeJsonParse(alert.top_features_json);
        if (!features || typeof features !== 'object' || Array.isArray(features)) return '';

        const zEntries = Object.entries(features)
            .filter(([k, v]) => /(?:_z|_zscore)$/i.test(k) && typeof v === 'number' && Number.isFinite(v));

        const rows = [];
        zEntries.forEach(([zKey, zVal]) => {
            const metricRoot = zKey.replace(/(?:_z|_zscore)$/i, '');
            const currentKey = Object.keys(features).find(k => {
                if (/(?:_z|_zscore)$/i.test(k)) return false;
                return k === metricRoot || k.startsWith(`${metricRoot}_`);
            });
            const currentVal = currentKey ? features[currentKey] : null;
            rows.push({
                metric: humanizeFeatureName(currentKey || metricRoot),
                current: formatFeatureValue(currentVal),
                z: zVal
            });
        });

        rows.sort((a, b) => Math.abs(b.z) - Math.abs(a.z));
        const topRows = rows.slice(0, 4);
        if (topRows.length === 0) return '';

        const rowHtml = topRows.map(row => {
            const direction = row.z >= 0 ? 'up' : 'down';
            const zText = `${row.z >= 0 ? '+' : ''}${row.z.toFixed(2)}z`;
            return `
                <div class="baseline-diff-row">
                    <span class="baseline-diff-metric">${row.metric}</span>
                    <span class="baseline-diff-value">${row.current}</span>
                    <span class="baseline-diff-delta ${direction}">${zText}</span>
                </div>
            `;
        }).join('');

        return `
            <div class="alert-baseline-diff">
                <div class="alert-baseline-head">Baseline Snapshot: ${alert.baseline_ref || 'personal baseline'}</div>
                <div class="alert-baseline-rows">${rowHtml}</div>
            </div>
        `;
    }

    function normalizeSeverity(alert, acknowledged) {
        if (acknowledged) return 'ack';
        if (alert.alert_type === 'geofence') return 'high';

        const raw = String(alert.severity || '').trim().toLowerCase();
        if (raw === 'critical' || raw === 'high') return 'high';
        if (raw === 'medium' || raw === 'moderate') return 'medium';
        if (raw === 'low' || raw === 'info') return 'low';

        const score = Number(alert.score);
        if (Number.isFinite(score)) {
            if (score >= 5) return 'high';
            if (score >= 2) return 'medium';
            return 'low';
        }
        return 'medium';
    }

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
        loadAdminApiKeyFromUrl();
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
            alertFilter.addEventListener('change', () => {
                persistAlertFilterState(getCurrentAlertFilterState(), 'custom');
                refreshAlertPresetButtons('custom');
                loadAlertsList();
            });
        }

        const alertSourceFilter = document.getElementById('alert-source-filter');
        if (alertSourceFilter) {
            alertSourceFilter.addEventListener('change', () => {
                persistAlertFilterState(getCurrentAlertFilterState(), 'custom');
                refreshAlertPresetButtons('custom');
                loadAlertsList();
            });
        }

        const alertParticipantFilter = document.getElementById('alert-participant-filter');
        if (alertParticipantFilter) {
            alertParticipantFilter.addEventListener('change', () => {
                persistAlertFilterState(getCurrentAlertFilterState(), 'custom');
                refreshAlertPresetButtons('custom');
                loadAlertsList();
            });
        }

        const alertLimitFilter = document.getElementById('alert-limit-filter');
        if (alertLimitFilter) {
            alertLimitFilter.addEventListener('change', () => {
                persistAlertFilterState(getCurrentAlertFilterState(), 'custom');
                refreshAlertPresetButtons('custom');
                loadAlertsList();
            });
        }

        document.querySelectorAll('.alert-preset-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const presetId = btn.getAttribute('data-preset');
                if (presetId) applyAlertPreset(presetId);
            });
        });

        // Refresh buttons
        const refreshMapBtn = document.getElementById('refresh-map-btn');
        if (refreshMapBtn) {
            refreshMapBtn.addEventListener('click', () => loadDashboardData());
        }

        const refreshAlertsBtn = document.getElementById('refresh-alerts-btn');
        if (refreshAlertsBtn) {
            refreshAlertsBtn.addEventListener('click', loadAlertsList);
        }

        const refreshAdminSecurityBtn = document.getElementById('refresh-admin-security-btn');
        if (refreshAdminSecurityBtn) {
            refreshAdminSecurityBtn.addEventListener('click', loadAdminControlPanel);
        }

        const addUserBtn = document.getElementById('add-user-btn');
        if (addUserBtn) {
            addUserBtn.addEventListener('click', handleAddUser);
        }
    }

    async function syncAuthProfileFromToken() {
        const token = (sessionStorage.getItem(AUTH_TOKEN_STORAGE) || '').trim();
        if (!token) return false;

        try {
            const response = await fetch(`${API_BASE}/auth/me`, {
                method: 'GET',
                headers: { Authorization: `Bearer ${token}` }
            });
            if (!response.ok) return false;

            const payload = await response.json();
            const user = payload?.user || {};
            const username = (user.username || sessionStorage.getItem('dp_ids_user') || '').trim();
            const role = (user.role || sessionStorage.getItem(AUTH_ROLE_STORAGE) || 'viewer').toLowerCase();

            if (username) sessionStorage.setItem('dp_ids_user', username);
            sessionStorage.setItem(AUTH_ROLE_STORAGE, role);
            return true;
        } catch (error) {
            console.warn('Failed to sync auth profile:', error);
            return false;
        }
    }

    // Check Authentication State
    async function checkAuthState() {
        const token = (sessionStorage.getItem(AUTH_TOKEN_STORAGE) || '').trim();
        if (!token) {
            showLogin();
            return;
        }

        const profileOk = await syncAuthProfileFromToken();
        if (!profileOk) {
            sessionStorage.removeItem('dp_ids_auth');
            sessionStorage.removeItem('dp_ids_user');
            sessionStorage.removeItem(AUTH_TOKEN_STORAGE);
            sessionStorage.removeItem(AUTH_ROLE_STORAGE);
            showLogin();
            return;
        }

        showDashboard();
    }

    // Handle Login
    async function handleLogin(e) {
        e.preventDefault();
        const username = (document.getElementById('username').value || '').trim();
        const password = document.getElementById('password').value || '';
        if (!username || !password) {
            alert('Username and password are required.');
            return;
        }

        try {
            const response = await fetch(`${API_BASE}/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            if (!response.ok) {
                alert('Invalid username or password.');
                return;
            }

            const payload = await response.json();
            const token = (payload.token || '').trim();
            if (!token) {
                alert('Login failed: missing session token.');
                return;
            }

            sessionStorage.setItem('dp_ids_auth', 'true');
            sessionStorage.setItem('dp_ids_user', payload.username || username);
            sessionStorage.setItem(AUTH_TOKEN_STORAGE, token);
            sessionStorage.setItem(AUTH_ROLE_STORAGE, (payload.role || 'viewer').toLowerCase());
            showDashboard();
        } catch (error) {
            console.error('Login failed:', error);
            alert('Login failed. Please try again.');
        }
    }

    // Handle Logout
    async function handleLogout() {
        const token = (sessionStorage.getItem(AUTH_TOKEN_STORAGE) || '').trim();
        if (token) {
            try {
                await fetch(`${API_BASE}/auth/logout`, {
                    method: 'POST',
                    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                    body: '{}'
                });
            } catch (e) {
                console.warn('Logout request failed:', e);
            }
        }
        sessionStorage.removeItem('dp_ids_auth');
        sessionStorage.removeItem('dp_ids_user');
        sessionStorage.removeItem(AUTH_TOKEN_STORAGE);
        sessionStorage.removeItem(AUTH_ROLE_STORAGE);
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
        const roleLower = getCurrentRole();
        const role = roleLower.toUpperCase();
        document.querySelector('.current-user').textContent = `${username} (${role})`;
        applyRolePermissions();
        restoreAlertFilterState();
        refreshAlertPresetButtons();

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
        if (!canAccessView(viewName)) {
            viewName = canReadAdminData() ? 'dashboard' : 'participants';
        }

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

    function loadAdminApiKeyFromUrl() {
        try {
            const params = new URLSearchParams(window.location.search);
            const key = (params.get('admin_api_key') || params.get('api_key') || '').trim();
            if (key) {
                localStorage.setItem(ADMIN_API_KEY_STORAGE, key);
            }
        } catch (e) {
            console.warn('Failed to read API key from URL:', e);
        }
    }

    function getAdminApiKey() {
        return (localStorage.getItem(ADMIN_API_KEY_STORAGE) || '').trim();
    }

    function setAdminApiKey(value) {
        if (!value || !value.trim()) return;
        localStorage.setItem(ADMIN_API_KEY_STORAGE, value.trim());
    }

    function ensureAdminApiKey(forcePrompt = false) {
        const existing = getAdminApiKey();
        if (existing && !forcePrompt) return existing;
        const entered = window.prompt('Enter Admin API key', existing || '');
        if (!entered || !entered.trim()) return null;
        setAdminApiKey(entered);
        return entered.trim();
    }

    async function apiRequest(endpoint, options = {}, retryAuth = true) {
        const headers = { ...(options.headers || {}) };
        const token = (sessionStorage.getItem(AUTH_TOKEN_STORAGE) || '').trim();
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        } else {
            const apiKey = getAdminApiKey();
            if (apiKey) headers['X-API-Key'] = apiKey;
        }

        let response = await fetch(`${API_BASE}${endpoint}`, {
            ...options,
            headers
        });

        if (response.status === 401 && retryAuth) {
            if (token) {
                await handleLogout();
                return response;
            } else {
                const newKey = ensureAdminApiKey(true);
                if (newKey) {
                    headers['X-API-Key'] = newKey;
                    response = await fetch(`${API_BASE}${endpoint}`, {
                        ...options,
                        headers
                    });
                }
            }
        }
        return response;
    }

    async function apiGet(endpoint) {
        try {
            const response = await apiRequest(endpoint);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return await response.json();
        } catch (error) {
            console.error(`API GET ${endpoint} failed:`, error);
            return null;
        }
    }

    async function apiPost(endpoint, data) {
        try {
            const response = await apiRequest(endpoint, {
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

    async function apiDelete(endpoint) {
        try {
            const response = await apiRequest(endpoint, { method: 'DELETE' });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const text = await response.text();
            return text ? JSON.parse(text) : { ok: true };
        } catch (error) {
            console.error(`API DELETE ${endpoint} failed:`, error);
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
            ...(signatureAlerts || []).map(a => ({ ...a, alert_type: 'signature', source_type: (a.source_type || getAlertSource(a)) }))
        ];

        const sortTs = (a) => a.triggered_at || a.created_at || a.hour_start || 0;
        merged.sort((a, b) => new Date(sortTs(b)).getTime() - new Date(sortTs(a)).getTime());
        state.alerts = merged;

        // Update stats
        document.getElementById('stat-participants').textContent = state.participants.length;
        const totalDevices = (state.participants || []).reduce((acc, participant) => {
            const explicitCount = Number(participant.device_count);
            if (Number.isFinite(explicitCount) && explicitCount > 0) return acc + explicitCount;
            if (Array.isArray(participant.devices) && participant.devices.length > 0) return acc + participant.devices.length;
            const fallbackCount = [participant.phone_device_id, participant.watch_device_id, participant.device_id]
                .filter(Boolean).length;
            return acc + (fallbackCount > 0 ? fallbackCount : 0);
        }, 0);
        document.getElementById('stat-devices').textContent = totalDevices;
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
            case 'settings':
                loadAdminControlPanel();
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
        const writeAllowed = canWriteAdminData();

        const participants = await apiGet('/participants');
        state.participants = participants || [];

        if (state.participants.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:#888;">No participants registered. Devices will auto-register when sending data.</td></tr>';
            return;
        }

        tbody.innerHTML = state.participants.map(p => {
            const phoneDeviceId = p.phone_device_id || '';
            const watchDeviceId = p.watch_device_id || '';
            const fallbackDeviceId = (!watchDeviceId && p.device_id) ? p.device_id : '';
            const primaryDeviceId = phoneDeviceId || fallbackDeviceId;
            const hasLocationDevice = !!primaryDeviceId;
            const sourceLabel = p.source_type === 'both'
                ? 'Phone + Watch'
                : (p.source_type === 'watch' ? 'Watch' : (p.source_type === 'phone' ? 'Phone' : 'Unknown'));
            const sourceBadgeClass = p.source_type === 'both'
                ? 'both'
                : (p.source_type === 'watch' ? 'watch' : 'phone');
            const deviceCell = `
                <div><span class="alert-source-badge ${sourceBadgeClass}">${sourceLabel}</span></div>
                <div><small>Phone: <code>${phoneDeviceId || '--'}</code></small></div>
                <div><small>Watch: <code>${watchDeviceId || '--'}</code></small></div>
            `;

            return `
                <tr id="row-${p.participant_id}">
                    <td>${p.participant_id.substring(0, 8)}...</td>
                    <td>${p.name}</td>
                    <td>${deviceCell}</td>
                    <td><span class="status-badge status-${p.status}">${p.status}</span></td>
                    <td><span class="risk-badge risk-${p.risk_level}">${p.risk_level}</span></td>
                    <td>
                        <button class="btn btn-sm btn-info" id="loc-btn-${p.participant_id}" ${hasLocationDevice ? '' : 'disabled'} onclick="toggleLocationMap('${p.participant_id}', '${primaryDeviceId}', '${p.name}')"><i style="margin-right: 5px;" class="fas fa-map-marker-alt"></i> Location</button>
                        <button class="btn btn-sm btn-secondary" onclick="openParticipantModal('${p.participant_id}')"><i style="margin-right: 5px;" class="fas fa-cog"></i> ${writeAllowed ? 'Settings' : 'View'}</button>
                        <button class="btn btn-sm btn-primary" onclick="openZonesModal('${p.participant_id}')"><i style="margin-right: 5px;" class="fas fa-map-marked-alt"></i> ${writeAllowed ? 'Red Zones' : 'View Zones'}</button>
                    </td>
                </tr>
                <tr id="map-row-${p.participant_id}" class="map-row hidden">
                    <td colspan="7">
                        <div class="participant-map-container">
                            <div class="map-header">
                                <span>Live Location: ${p.name}</span>
                                <button class="btn btn-sm btn-secondary" ${hasLocationDevice ? '' : 'disabled'} onclick="refreshParticipantMap('${p.participant_id}', '${primaryDeviceId}')"><i style="margin-right: 5px;" class="fas fa-sync-alt"></i> Refresh</button>
                            </div>
                            <div id="map-${p.participant_id}" class="participant-map"></div>
                            <div id="map-info-${p.participant_id}" class="map-info"></div>
                        </div>
                    </td>
                </tr>
            `;
        }).join('');
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
        const writeAllowed = canWriteAdminData();
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
        persistAlertFilterState({ status: filter, source: sourceFilter, participant: participantFilter, limit: alertLimit }, getCurrentPresetId());

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
            ...(signatureAlerts || []).map(a => ({ ...a, alert_type: 'signature', source_type: (a.source_type || getAlertSource(a)) }))
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
            filteredAlerts = filteredAlerts.filter(a => ((a.source_type || getAlertSource(a) || '').toLowerCase()) === sourceFilter);
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
            const latestTs = participantAlerts.length > 0 ? alertTimestampValue(participantAlerts[0]) : 0;
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
            const phoneCount = participantAlerts.filter(alert => {
                const source = (alert.source_type || getAlertSource(alert) || '').toLowerCase();
                return source === 'phone' || source === 'both';
            }).length;
            const watchCount = participantAlerts.filter(alert => {
                const source = (alert.source_type || getAlertSource(alert) || '').toLowerCase();
                return source === 'watch' || source === 'both';
            }).length;
            const participantKey = String(participantId);
            const defaultExpanded = participantFilter !== 'all';
            const isExpanded = state.alertGroupExpanded[participantKey] !== undefined
                ? !!state.alertGroupExpanded[participantKey]
                : defaultExpanded;
            const groupDomId = participantGroupDomId(participantKey);
            const density = buildAlertDensitySparkline(participantAlerts, 12);
            const participantBody = participantAlerts.map(alert => {
                const isGeofence = alert.alert_type === 'geofence';
                const isSignature = alert.alert_type === 'signature';
                const sourceType = (alert.source_type || getAlertSource(alert) || 'phone').toLowerCase();

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
                const severityLevel = normalizeSeverity(alert, acknowledged);
                const severityLabel = severityLevel === 'ack' ? 'ACK' : severityLevel.toUpperCase();
                const ackText = acknowledged ? `| Acknowledged by ${alert.acknowledged_by || ''}` : '';

                const ackBtn = (!acknowledged)
                    ? (writeAllowed && isGeofence
                        ? `<button class="btn btn-primary alert-ack-btn" onclick="acknowledgeAlert('${alert.alert_id}')"><i class="fas fa-check"></i><span>Acknowledge</span></button>`
                        : (writeAllowed
                            ? `<button class="btn btn-primary alert-ack-btn" onclick="acknowledgeSignatureAlert(${alert.id})"><i class="fas fa-check"></i><span>Acknowledge</span></button>`
                            : '')
                    )
                    : '';

                const shownTime = isSignature
                    ? (alert.created_at || alert.hour_start)
                    : alert.triggered_at;
                const baselineDiffHtml = buildBaselineDiffHtml(alert);

                return `
                <div class="alert-item severity-${severityLevel}">
                    <div class="alert-icon">${acknowledged ? '<i class="fas fa-check-circle"></i>' : '<i class="fas fa-exclamation-circle"></i>'}</div>
                    <div class="alert-content">
                        <div class="alert-title">${title} <span class="severity-pill severity-${severityLevel}">${severityLabel}</span></div>
                        <div class="alert-description">${desc}</div>
                        ${baselineDiffHtml}
                        <div class="alert-meta">
                            <span class="alert-source-badge ${sourceType}">${sourceType === 'watch' ? 'Watch' : (sourceType === 'both' ? 'Phone + Watch' : 'Phone')}</span>
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
                    <div class="participant-alert-header ${isExpanded ? 'expanded' : 'collapsed'}" onclick="toggleParticipantAlertGroup('${participantKey.replace(/'/g, "\\'")}')">
                        <div class="participant-alert-title-wrap">
                            <button class="participant-alert-toggle" type="button">
                                <i class="fas ${isExpanded ? 'fa-chevron-down' : 'fa-chevron-right'}" id="participant-alert-toggle-icon-${groupDomId}"></i>
                            </button>
                            <div class="participant-alert-title">${participantId}</div>
                            <span class="alert-count-chip total">${participantAlerts.length}</span>
                            <div class="participant-alert-density" title="Recent alert density">${density.barsHtml}</div>
                        </div>
                        <div class="participant-alert-count">
                            <span class="alert-count-chip phone">Phone ${phoneCount}</span>
                            <span class="alert-count-chip watch">Watch ${watchCount}</span>
                        </div>
                    </div>
                    <div class="participant-alert-body ${isExpanded ? '' : 'hidden'}" id="participant-alert-body-${groupDomId}">
                        ${participantBody}
                    </div>
                </div>
            `;
        }).join('');
        state.loadingAlerts = false;
    }

    window.toggleParticipantAlertGroup = function (participantId) {
        const key = String(participantId || '');
        if (!key) return;
        const current = !!state.alertGroupExpanded[key];
        state.alertGroupExpanded[key] = !current;

        const groupDomId = participantGroupDomId(key);
        const body = document.getElementById(`participant-alert-body-${groupDomId}`);
        const icon = document.getElementById(`participant-alert-toggle-icon-${groupDomId}`);
        if (body) body.classList.toggle('hidden', current);
        if (icon) {
            icon.classList.toggle('fa-chevron-right', current);
            icon.classList.toggle('fa-chevron-down', !current);
        }
    };

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
        const explicit = (alert?.source_type || '').toLowerCase();
        if (explicit === 'phone' || explicit === 'watch' || explicit === 'both') return explicit;
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
        if (!canWriteAdminData()) {
            alert('Your role is read-only. Alert acknowledgment is disabled.');
            return;
        }
        const username = sessionStorage.getItem('dp_ids_user') || 'admin';
        const result = await apiPost(`/alerts/${alertId}/acknowledge?by=${username}`, {});
        if (result && result.ok) {
            loadAlertsList();
            loadDashboardData(); // Refresh dashboard stats too
        }
    };



    // Global function for acknowledge SIGNATURE alert button
    window.acknowledgeSignatureAlert = async function (id) {
        if (!canWriteAdminData()) {
            alert('Your role is read-only. Alert acknowledgment is disabled.');
            return;
        }
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
        const writeAllowed = canWriteAdminData();

        const primaryDeviceId = participant.phone_device_id || participant.watch_device_id || participant.device_id || '';
        document.getElementById('modal-participant-id').value = participant.participant_id;
        document.getElementById('modal-participant-name').value = participant.name;
        document.getElementById('modal-device-id').value = primaryDeviceId;
        document.getElementById('modal-red-zone-radius').value = participant.red_zone_radius || 300;
        document.getElementById('modal-risk-level').value = participant.risk_level || 'low';
        document.getElementById('modal-status').value = participant.status || 'active';

        document.getElementById('modal-participant-name').disabled = !writeAllowed;
        document.getElementById('modal-red-zone-radius').disabled = !writeAllowed;
        document.getElementById('modal-risk-level').disabled = !writeAllowed;
        document.getElementById('modal-status').disabled = !writeAllowed;
        const saveButton = document.getElementById('modal-save-participant-btn');
        if (saveButton) saveButton.style.display = writeAllowed ? '' : 'none';

        document.getElementById('participant-modal').classList.remove('hidden');
    };

    window.closeParticipantModal = function () {
        document.getElementById('participant-modal').classList.add('hidden');
    };

    window.saveParticipant = async function () {
        if (!canWriteAdminData()) {
            alert('Your role is read-only. Participant updates are disabled.');
            return;
        }
        const participantId = document.getElementById('modal-participant-id').value;
        const data = {
            participant_id: participantId,
            device_id: document.getElementById('modal-device-id').value,
            name: document.getElementById('modal-participant-name').value,
            red_zone_radius: parseInt(document.getElementById('modal-red-zone-radius').value),
            risk_level: document.getElementById('modal-risk-level').value,
            status: document.getElementById('modal-status').value
        };

        if (!data.device_id) {
            delete data.device_id;
        }

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
        applyZonesModalPermissions();

        await loadZonesTable(participantId);

        document.getElementById('zones-modal').classList.remove('hidden');
    };

    window.closeZonesModal = function () {
        document.getElementById('zones-modal').classList.add('hidden');
    };

    async function loadZonesTable(participantId) {
        const tbody = document.getElementById('zones-table');
        if (!tbody) return;
        const writeAllowed = canWriteAdminData();

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
                    ${writeAllowed ? `<button class="btn btn-sm btn-danger" onclick="deleteRedZone('${zone.zone_id}')"><i style="margin-right: 5px;" class="fas fa-trash"></i> Delete</button>` : '<span style="color:#888;">Read-only</span>'}
                </td>
            </tr>
        `).join('');
    }

    function applyZonesModalPermissions() {
        const writeAllowed = canWriteAdminData();
        const addZoneCard = document.getElementById('add-zone-card');
        if (addZoneCard) addZoneCard.style.display = writeAllowed ? '' : 'none';
        const readonlyNote = document.getElementById('zones-readonly-note');
        if (readonlyNote) readonlyNote.classList.toggle('hidden', writeAllowed);
    }

    window.addRedZone = async function () {
        if (!canWriteAdminData()) {
            alert('Your role is read-only. Red zone changes are disabled.');
            return;
        }
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
        if (!canWriteAdminData()) {
            alert('Your role is read-only. Red zone changes are disabled.');
            return;
        }
        if (!confirm('Are you sure you want to delete this red zone?')) return;

        const participantId = document.getElementById('zones-participant-id').value;
        const result = await apiDelete(`/zones/${zoneId}`);

        if (result && result.ok) {
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

        const flattened = [];
        const seen = new Set();

        participants.forEach(p => {
            const fallbackDevices = [];
            if (p.phone_device_id) fallbackDevices.push({ device_id: p.phone_device_id, device_type: 'phone' });
            if (p.watch_device_id && p.watch_device_id !== p.phone_device_id) fallbackDevices.push({ device_id: p.watch_device_id, device_type: 'watch' });
            if ((!p.phone_device_id && !p.watch_device_id) && p.device_id) fallbackDevices.push({ device_id: p.device_id, device_type: (p.source_type || 'unknown') });

            const devices = Array.isArray(p.devices) && p.devices.length > 0 ? p.devices : fallbackDevices;
            devices.forEach(device => {
                const deviceId = device?.device_id;
                if (!deviceId || seen.has(deviceId)) return;
                seen.add(deviceId);
                const deviceType = (device?.device_type || p.source_type || 'unknown').toLowerCase();
                flattened.push({
                    participant_id: p.participant_id,
                    participant_name: p.name,
                    device_id: deviceId,
                    device_type: deviceType,
                    status: p.status,
                    updated_at: p.updated_at,
                    battery_percentage: deviceType === 'phone' ? p.percentage : null,
                    charging_status: deviceType === 'phone' ? p.charging_status : null,
                });
            });
        });

        if (flattened.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:#888;">No devices registered</td></tr>';
            return;
        }

        tbody.innerHTML = flattened.map(d => {
            const batteryStr = d.battery_percentage !== null && d.battery_percentage !== undefined
                ? `${Math.round(d.battery_percentage)}% (${d.charging_status || 'unknown'})`
                : '--';
            const sourceType = (d.device_type || 'unknown').toLowerCase();
            const sourceLabel = sourceType === 'watch' ? 'Watch' : (sourceType === 'phone' ? 'Phone' : 'Unknown');
            const sourceBadgeClass = sourceType === 'watch' ? 'watch' : 'phone';

            return `
                <tr>
                    <td><code>${d.device_id}</code></td>
                    <td>${d.participant_name || d.participant_id}</td>
                    <td><span class="alert-source-badge ${sourceBadgeClass}">${sourceLabel}</span></td>
                    <td>${formatTimestamp(d.updated_at) || 'Unknown'}</td>
                    <td id="battery-${d.device_id}">${batteryStr}</td>
                    <td>8</td>
                    <td><span class="status-badge status-${d.status === 'active' ? 'active' : 'inactive'}">${d.status}</span></td>
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

    async function loadUsersTable() {
        const tbody = document.getElementById('users-table');
        if (!tbody) return;

        const users = await apiGet('/users');
        if (!users || !Array.isArray(users)) {
            tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color:#888;">Failed to load users</td></tr>';
            return;
        }

        if (users.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color:#888;">No users found</td></tr>';
            return;
        }

        tbody.innerHTML = users.map(user => `
            <tr>
                <td>${user.username}</td>
                <td>${String(user.role || '').toUpperCase()}</td>
                <td>${user.email || ''}</td>
                <td>${formatTimestamp(user.last_login_at || user.created_at || user.updated_at)}</td>
                <td><span class="status-badge status-${user.status}">${user.status}</span></td>
                <td>
                    <button class="btn btn-sm btn-secondary" onclick="window.editUser('${user.username}')">Edit</button>
                </td>
            </tr>
        `).join('');
    }

    window.editUser = async function (username) {
        const role = (sessionStorage.getItem(AUTH_ROLE_STORAGE) || '').toLowerCase();
        if (role !== 'admin') {
            alert('Only admin users can edit users.');
            return;
        }
        const newRole = (window.prompt('Role (admin/analyst/viewer/doctor/ingest)', 'viewer') || '').trim().toLowerCase();
        if (!newRole) return;
        const status = (window.prompt('Status (active/inactive)', 'active') || '').trim().toLowerCase();
        if (!status) return;
        const password = window.prompt('New password (leave empty to keep current)', '') || '';
        const result = await apiPost('/users', { username, role: newRole, status, password });
        if (result && result.ok) {
            await loadUsersTable();
            alert('User updated.');
        } else {
            alert('Failed to update user.');
        }
    };

    async function handleAddUser() {
        const role = (sessionStorage.getItem(AUTH_ROLE_STORAGE) || '').toLowerCase();
        if (role !== 'admin') {
            alert('Only admin users can add users.');
            return;
        }
        const username = (window.prompt('Username') || '').trim();
        if (!username) return;
        const password = window.prompt('Password') || '';
        if (!password) {
            alert('Password is required.');
            return;
        }
        const newRole = (window.prompt('Role (admin/analyst/viewer/doctor/ingest)', 'viewer') || 'viewer').trim().toLowerCase();
        const email = (window.prompt('Email (optional)', '') || '').trim();
        const fullName = (window.prompt('Full name (optional)', '') || '').trim();
        const result = await apiPost('/users', {
            username,
            password,
            role: newRole,
            email,
            full_name: fullName,
            status: 'active'
        });
        if (result && result.ok) {
            await loadUsersTable();
            alert('User created.');
        } else {
            alert('Failed to create user.');
        }
    }

    // ---- ADMIN CONTROL PANEL ----

    async function loadAdminControlPanel() {
        const role = (sessionStorage.getItem(AUTH_ROLE_STORAGE) || '').toLowerCase();
        if (role !== 'admin') return;

        const [status, sessions, lockouts, audit] = await Promise.all([
            apiGet('/admin/security-status'),
            apiGet('/admin/sessions?limit=200'),
            apiGet('/admin/login-lockouts?limit=100'),
            apiGet('/admin/audit?limit=200')
        ]);

        renderSecurityStatus(status);
        renderActiveSessions(sessions);
        renderLoginLockouts(lockouts);
        renderSecurityAudit(audit);
    }

    function renderSecurityStatus(status) {
        const minLengthInput = document.getElementById('security-password-min-length');
        const lockoutPolicyInput = document.getElementById('security-lockout-policy');
        const summaryInput = document.getElementById('security-status-summary');
        if (!minLengthInput || !lockoutPolicyInput || !summaryInput) return;

        if (!status || !status.password_policy || !status.auth_policy) {
            minLengthInput.value = 'Unavailable';
            lockoutPolicyInput.value = 'Unavailable';
            summaryInput.value = 'Unavailable';
            return;
        }

        const pp = status.password_policy || {};
        const ap = status.auth_policy || {};
        minLengthInput.value = String(pp.min_length || '');
        lockoutPolicyInput.value =
            `${ap.max_failed_attempts || ''} failed in ${ap.attempt_window_minutes || ''}m -> ${ap.lockout_minutes || ''}m lock`;
        summaryInput.value =
            `${status.active_users || 0} users / ${status.active_sessions || 0} sessions / ${status.locked_accounts || 0} locked`;
    }

    function renderActiveSessions(sessions) {
        const tbody = document.getElementById('admin-sessions-table');
        if (!tbody) return;
        if (!Array.isArray(sessions) || sessions.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:#888;">No active sessions</td></tr>';
            return;
        }

        tbody.innerHTML = sessions.map(s => `
            <tr>
                <td>${s.id}</td>
                <td>${s.username || ''}</td>
                <td>${String(s.role || '').toUpperCase()}</td>
                <td>${s.client_ip || ''}</td>
                <td>${formatTimestamp(s.last_seen_at || s.created_at)}</td>
                <td>${formatTimestamp(s.expires_at)}</td>
                <td><button class="btn btn-sm btn-danger" onclick="window.revokeAuthSession(${s.id})">Revoke</button></td>
            </tr>
        `).join('');
    }

    function renderLoginLockouts(lockouts) {
        const tbody = document.getElementById('admin-lockouts-table');
        if (!tbody) return;
        if (!Array.isArray(lockouts) || lockouts.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:#888;">No lockouts</td></tr>';
            return;
        }

        tbody.innerHTML = lockouts.map(l => `
            <tr>
                <td>${l.username || ''}</td>
                <td>${l.failed_count || 0}</td>
                <td>${l.last_ip || ''}</td>
                <td>${formatTimestamp(l.locked_until)}</td>
                <td>${formatTimestamp(l.last_failed_at)}</td>
            </tr>
        `).join('');
    }

    function renderSecurityAudit(events) {
        const tbody = document.getElementById('admin-audit-table');
        if (!tbody) return;
        if (!Array.isArray(events) || events.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; color:#888;">No audit events</td></tr>';
            return;
        }

        tbody.innerHTML = events.map(e => `
            <tr>
                <td>${formatTimestamp(e.event_at)}</td>
                <td>${e.actor || ''}</td>
                <td>${e.action || ''}</td>
                <td>${e.target_type || ''}:${e.target_id || ''}</td>
            </tr>
        `).join('');
    }

    window.revokeAuthSession = async function (id) {
        const role = (sessionStorage.getItem(AUTH_ROLE_STORAGE) || '').toLowerCase();
        if (role !== 'admin') {
            alert('Only admin users can revoke sessions.');
            return;
        }
        if (!id) return;
        const result = await apiPost(`/admin/sessions/${id}/revoke`, {});
        if (result && result.ok) {
            await loadAdminControlPanel();
        } else {
            alert('Failed to revoke session.');
        }
    };

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
