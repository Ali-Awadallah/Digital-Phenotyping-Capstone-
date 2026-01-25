/**
 * Digital Phenotyping IDS - Clinical Dashboard Application
 * Frontend JavaScript for UI interactions and data display
 */

(function () {
    'use strict';

    // Application State
    const state = {
        currentView: 'dashboard',
        isAuthenticated: false,
        lastSync: new Date()
    };

    // Mock Data (to be replaced with real API calls)
    const mockData = {
        participants: [
            { id: 'P001', name: 'Participant A', deviceId: 'demo-phone', status: 'active', lastActivity: '2 min ago', riskLevel: 'low' },
            { id: 'P002', name: 'Participant B', deviceId: 'device-002', status: 'active', lastActivity: '15 min ago', riskLevel: 'moderate' },
            { id: 'P003', name: 'Participant C', deviceId: 'device-003', status: 'inactive', lastActivity: '3 hours ago', riskLevel: 'high' },
            { id: 'P004', name: 'Participant D', deviceId: 'device-004', status: 'active', lastActivity: '5 min ago', riskLevel: 'low' },
            { id: 'P005', name: 'Participant E', deviceId: 'device-005', status: 'active', lastActivity: '1 hour ago', riskLevel: 'moderate' }
        ],
        devices: [
            { deviceId: 'demo-phone', participant: 'Participant A', lastSeen: '2 min ago', battery: 85, sensorsActive: 6, status: 'online' },
            { deviceId: 'device-002', participant: 'Participant B', lastSeen: '15 min ago', battery: 72, sensorsActive: 6, status: 'online' },
            { deviceId: 'device-003', participant: 'Participant C', lastSeen: '3 hours ago', battery: 12, sensorsActive: 0, status: 'offline' },
            { deviceId: 'device-004', participant: 'Participant D', lastSeen: '5 min ago', battery: 94, sensorsActive: 6, status: 'online' },
            { deviceId: 'device-005', participant: 'Participant E', lastSeen: '1 hour ago', battery: 45, sensorsActive: 4, status: 'online' }
        ],
        alerts: [
            {
                id: 'A001',
                type: 'critical',
                title: 'Prolonged Inactivity Detected',
                description: 'Participant C has shown no device activity for over 3 hours. Immediate follow-up recommended.',
                participant: 'Participant C',
                time: '10 min ago'
            },
            {
                id: 'A002',
                type: 'warning',
                title: 'Elevated Risk Score',
                description: 'Participant B risk score has increased to 68. Behavioral pattern changes detected.',
                participant: 'Participant B',
                time: '45 min ago'
            },
            {
                id: 'A003',
                type: 'warning',
                title: 'Low Battery Warning',
                description: 'Device for Participant C has critically low battery (12%). Data collection may be interrupted.',
                participant: 'Participant C',
                time: '1 hour ago'
            },
            {
                id: 'A004',
                type: 'info',
                title: 'New Device Registered',
                description: 'Device demo-phone has been successfully registered and is now collecting data.',
                participant: 'Participant A',
                time: '2 hours ago'
            }
        ],
        users: [
            { username: 'admin', role: 'Administrator', email: 'admin@clinic.org', lastLogin: 'Just now', status: 'active' },
            { username: 'dr.smith', role: 'Clinician', email: 'smith@clinic.org', lastLogin: '2 hours ago', status: 'active' },
            { username: 'nurse.jones', role: 'Staff', email: 'jones@clinic.org', lastLogin: '1 day ago', status: 'active' },
            { username: 'researcher', role: 'Researcher', email: 'research@clinic.org', lastLogin: '3 days ago', status: 'inactive' }
        ],
        stats: {
            participants: 5,
            devices: 4,
            alerts: 3,
            datapoints: 12847
        }
    };

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
    }

    // Check Authentication State
    function checkAuthState() {
        // For demo purposes, check session storage
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

    // Load Dashboard Data
    function loadDashboardData() {
        // Update stats
        document.getElementById('stat-participants').textContent = mockData.stats.participants;
        document.getElementById('stat-devices').textContent = mockData.stats.devices;
        document.getElementById('stat-alerts').textContent = mockData.stats.alerts;
        document.getElementById('stat-datapoints').textContent = formatNumber(mockData.stats.datapoints);

        // Update last sync time
        document.getElementById('last-sync').textContent = formatTime(state.lastSync);

        // Load recent alerts
        loadRecentAlerts();
    }

    // Load View-Specific Data
    function loadViewData(viewName) {
        switch (viewName) {
            case 'participants':
                loadParticipantsTable();
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

    // Load Recent Alerts Table
    function loadRecentAlerts() {
        const tbody = document.getElementById('recent-alerts-table');
        if (!tbody) return;

        const recentAlerts = mockData.alerts.slice(0, 4);
        tbody.innerHTML = recentAlerts.map(alert => `
            <tr>
                <td>${alert.time}</td>
                <td>${alert.participant}</td>
                <td>${alert.title}</td>
                <td><span class="risk-badge risk-${getSeverityClass(alert.type)}">${alert.type}</span></td>
            </tr>
        `).join('');
    }

    // Load Participants Table
    function loadParticipantsTable() {
        const tbody = document.getElementById('participants-table');
        if (!tbody) return;

        tbody.innerHTML = mockData.participants.map(participant => `
            <tr>
                <td>${participant.id}</td>
                <td>${participant.name}</td>
                <td><code>${participant.deviceId}</code></td>
                <td><span class="status-badge status-${participant.status}">${participant.status}</span></td>
                <td>${participant.lastActivity}</td>
                <td><span class="risk-badge risk-${participant.riskLevel}">${participant.riskLevel}</span></td>
                <td>
                    <button class="btn btn-sm btn-secondary">View</button>
                </td>
            </tr>
        `).join('');
    }

    // Load Alerts List
    function loadAlertsList(filter = 'all') {
        const container = document.getElementById('alerts-list');
        if (!container) return;

        let alerts = mockData.alerts;
        if (filter !== 'all') {
            alerts = alerts.filter(a => a.type === filter);
        }

        container.innerHTML = alerts.map(alert => `
            <div class="alert-item ${alert.type}">
                <div class="alert-icon">${getAlertIcon(alert.type)}</div>
                <div class="alert-content">
                    <div class="alert-title">${alert.title}</div>
                    <div class="alert-description">${alert.description}</div>
                    <div class="alert-meta">${alert.participant} | ${alert.time}</div>
                </div>
                <div class="alert-actions">
                    <button class="btn btn-sm btn-secondary">Acknowledge</button>
                    <button class="btn btn-sm btn-primary">View Details</button>
                </div>
            </div>
        `).join('');
    }

    // Filter Alerts
    function filterAlerts() {
        const filter = document.getElementById('alert-filter').value;
        loadAlertsList(filter);
    }

    // Load Devices Table
    function loadDevicesTable() {
        const tbody = document.getElementById('devices-table');
        if (!tbody) return;

        tbody.innerHTML = mockData.devices.map(device => `
            <tr>
                <td><code>${device.deviceId}</code></td>
                <td>${device.participant}</td>
                <td>${device.lastSeen}</td>
                <td>${device.battery}%</td>
                <td>${device.sensorsActive}</td>
                <td><span class="status-badge status-${device.status === 'online' ? 'active' : 'inactive'}">${device.status}</span></td>
            </tr>
        `).join('');
    }

    // Load Users Table
    function loadUsersTable() {
        const tbody = document.getElementById('users-table');
        if (!tbody) return;

        tbody.innerHTML = mockData.users.map(user => `
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

    // Utility Functions
    function formatNumber(num) {
        return num.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    function formatTime(date) {
        return date.toLocaleTimeString('en-US', {
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    function getSeverityClass(type) {
        const map = {
            critical: 'high',
            warning: 'moderate',
            info: 'low'
        };
        return map[type] || 'low';
    }

    function getAlertIcon(type) {
        const icons = {
            critical: '!',
            warning: '!',
            info: 'i'
        };
        return icons[type] || '!';
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

})();
