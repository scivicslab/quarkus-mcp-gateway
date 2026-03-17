(function () {
    'use strict';

    var REFRESH_INTERVAL = 10000; // 10 seconds
    var refreshTimer = null;

    // --- Theme ---
    var themeSelect = document.getElementById('theme-select');
    var savedTheme = localStorage.getItem('mcp-gateway-theme') || 'dark-catppuccin';
    document.documentElement.setAttribute('data-theme', savedTheme);
    themeSelect.value = savedTheme;

    themeSelect.addEventListener('change', function () {
        var theme = themeSelect.value;
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem('mcp-gateway-theme', theme);
    });

    // --- Server list ---
    var tableArea = document.getElementById('server-table-area');
    var lastRefreshEl = document.getElementById('last-refresh');
    var currentServers = [];
    var sortColumn = 'name';
    var sortAsc = true;

    function escapeHtml(s) {
        if (!s) return '';
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(s));
        return div.innerHTML;
    }

    function formatTime(iso) {
        if (!iso) return '-';
        var d = new Date(iso);
        var hh = String(d.getHours()).padStart(2, '0');
        var mm = String(d.getMinutes()).padStart(2, '0');
        var ss = String(d.getSeconds()).padStart(2, '0');
        return hh + ':' + mm + ':' + ss;
    }

    function sortServers(servers) {
        var sorted = servers.slice();
        sorted.sort(function (a, b) {
            var va, vb;
            switch (sortColumn) {
                case 'name':        va = a.name || ''; vb = b.name || ''; break;
                case 'url':         va = a.url || ''; vb = b.url || ''; break;
                case 'description': va = a.description || ''; vb = b.description || ''; break;
                case 'status':      va = a.healthy ? 1 : 0; vb = b.healthy ? 1 : 0; break;
                case 'lastCheck':   va = a.lastHealthCheck || ''; vb = b.lastHealthCheck || ''; break;
                default:            va = a.name || ''; vb = b.name || '';
            }
            if (typeof va === 'string') {
                va = va.toLowerCase(); vb = vb.toLowerCase();
            }
            if (va < vb) return sortAsc ? -1 : 1;
            if (va > vb) return sortAsc ? 1 : -1;
            return 0;
        });
        return sorted;
    }

    function sortIndicator(col) {
        if (sortColumn !== col) return '';
        return sortAsc ? ' \u25B2' : ' \u25BC';
    }

    function renderServers(servers) {
        if (!servers || servers.length === 0) {
            tableArea.innerHTML = '<p class="empty-message">No servers registered yet.</p>';
            return;
        }

        var sorted = sortServers(servers);

        var columns = [
            { key: 'name', label: 'Name' },
            { key: 'url', label: 'URL' },
            { key: 'description', label: 'Description' },
            { key: 'name', label: 'Proxy Endpoint', sortable: false },
            { key: 'status', label: 'Status' },
            { key: 'lastCheck', label: 'Last Check' }
        ];

        var html = '<table><thead><tr>';
        for (var c = 0; c < columns.length; c++) {
            var col = columns[c];
            if (col.sortable === false) {
                html += '<th>' + col.label + '</th>';
            } else {
                html += '<th class="sortable" data-sort="' + col.key + '">' +
                    col.label + sortIndicator(col.key) + '</th>';
            }
        }
        html += '<th></th></tr></thead><tbody>';

        for (var i = 0; i < sorted.length; i++) {
            var s = sorted[i];
            var statusClass = s.healthy ? 'status-healthy' : 'status-unhealthy';
            var statusText = s.healthy ? 'healthy' : 'down';
            html += '<tr>' +
                '<td class="server-name">' + escapeHtml(s.name) + '</td>' +
                '<td><code>' + escapeHtml(s.url) + '</code></td>' +
                '<td>' + escapeHtml(s.description || '') + '</td>' +
                '<td><code>POST /mcp/' + escapeHtml(s.name) + '</code></td>' +
                '<td class="' + statusClass + '">' + statusText + '</td>' +
                '<td class="timestamp">' + formatTime(s.lastHealthCheck) + '</td>' +
                '<td><button class="btn-delete" data-name="' + escapeHtml(s.name) + '">Delete</button></td>' +
                '</tr>';
        }

        html += '</tbody></table>';
        tableArea.innerHTML = html;

        // Attach sort handlers
        var sortHeaders = tableArea.querySelectorAll('.sortable');
        for (var h = 0; h < sortHeaders.length; h++) {
            sortHeaders[h].addEventListener('click', function () {
                var col = this.getAttribute('data-sort');
                if (sortColumn === col) {
                    sortAsc = !sortAsc;
                } else {
                    sortColumn = col;
                    sortAsc = true;
                }
                renderServers(currentServers);
            });
        }

        // Attach delete handlers
        var deleteButtons = tableArea.querySelectorAll('.btn-delete');
        for (var j = 0; j < deleteButtons.length; j++) {
            deleteButtons[j].addEventListener('click', function () {
                deleteServer(this.getAttribute('data-name'));
            });
        }
    }

    function loadServers() {
        fetch('/api/servers')
            .then(function (res) { return res.json(); })
            .then(function (data) {
                currentServers = data;
                renderServers(data);
                var now = new Date();
                lastRefreshEl.textContent = 'Last refresh: ' + formatTime(now.toISOString());
            })
            .catch(function (err) {
                tableArea.innerHTML = '<p class="empty-message">Failed to load servers: ' + escapeHtml(String(err)) + '</p>';
            });
    }

    function deleteServer(name) {
        if (!confirm('Delete server "' + name + '"?')) return;
        fetch('/api/servers/' + encodeURIComponent(name), { method: 'DELETE' })
            .then(function () { loadServers(); })
            .catch(function (err) { alert('Delete failed: ' + err); });
    }

    // --- Registration form ---
    var registerForm = document.getElementById('register-form');
    registerForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var name = document.getElementById('reg-name').value.trim();
        var url = document.getElementById('reg-url').value.trim();
        var desc = document.getElementById('reg-desc').value.trim();

        fetch('/api/servers', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name, url: url, description: desc })
        })
            .then(function () {
                registerForm.reset();
                loadServers();
            })
            .catch(function (err) { alert('Registration failed: ' + err); });
    });

    // --- Service Discovery ---
    var discoverBtn = document.getElementById('discover-btn');
    var discoverStatus = document.getElementById('discover-status');
    var discoverResults = document.getElementById('discover-results');
    var discoverHost = document.getElementById('discover-host');
    var discoverPorts = document.getElementById('discover-ports');

    // Restore saved scan settings
    var savedHost = localStorage.getItem('mcp-gateway-discover-host');
    var savedPorts = localStorage.getItem('mcp-gateway-discover-ports');
    if (savedHost) discoverHost.value = savedHost;
    if (savedPorts) discoverPorts.value = savedPorts;

    discoverBtn.addEventListener('click', function () {
        var host = discoverHost.value.trim() || 'localhost';
        var ports = discoverPorts.value.trim() || '28000-29000';

        // Save settings
        localStorage.setItem('mcp-gateway-discover-host', host);
        localStorage.setItem('mcp-gateway-discover-ports', ports);

        discoverBtn.disabled = true;
        discoverBtn.textContent = 'Scanning...';
        discoverStatus.textContent = '';
        discoverResults.innerHTML = '';

        fetch('/api/discover', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ host: host, ports: ports })
        })
            .then(function (res) { return res.json(); })
            .then(function (data) {
                discoverBtn.disabled = false;
                discoverBtn.textContent = 'Discover';

                var servers = data.discovered || [];
                discoverStatus.textContent = servers.length + ' found / ' + data.scannedCount + ' scanned (' + data.elapsedMs + 'ms)';

                if (servers.length === 0) {
                    discoverResults.innerHTML = '<p class="empty-message">No MCP servers discovered.</p>';
                    return;
                }

                var html = '<table>' +
                    '<thead><tr>' +
                    '<th><input type="checkbox" id="discover-select-all" checked></th>' +
                    '<th>Name</th><th>URL</th><th>Version</th><th>Status</th>' +
                    '</tr></thead><tbody>';

                for (var i = 0; i < servers.length; i++) {
                    var s = servers[i];
                    var statusText = s.alreadyRegistered ? 'registered' : 'new';
                    var statusClass = s.alreadyRegistered ? 'status-unhealthy' : 'status-healthy';
                    var checked = s.alreadyRegistered ? '' : 'checked';
                    html += '<tr>' +
                        '<td><input type="checkbox" class="discover-check" ' + checked +
                        ' data-idx="' + i + '"></td>' +
                        '<td class="server-name">' + escapeHtml(s.name) + '</td>' +
                        '<td><code>' + escapeHtml(s.url) + '</code></td>' +
                        '<td>' + escapeHtml(s.version || '-') + '</td>' +
                        '<td class="' + statusClass + '">' + statusText + '</td>' +
                        '</tr>';
                }

                html += '</tbody></table>';
                html += '<button id="register-discovered-btn" class="btn-register">Register Selected</button>';
                discoverResults.innerHTML = html;

                // Store discovered data for registration
                discoverResults._discoveredData = servers;

                // Select-all toggle
                document.getElementById('discover-select-all').addEventListener('change', function () {
                    var checked = this.checked;
                    var checkboxes = discoverResults.querySelectorAll('.discover-check');
                    for (var j = 0; j < checkboxes.length; j++) {
                        checkboxes[j].checked = checked;
                    }
                });

                // Register selected
                document.getElementById('register-discovered-btn').addEventListener('click', function () {
                    var checkboxes = discoverResults.querySelectorAll('.discover-check:checked');
                    var toRegister = [];
                    for (var j = 0; j < checkboxes.length; j++) {
                        var idx = parseInt(checkboxes[j].getAttribute('data-idx'));
                        toRegister.push(discoverResults._discoveredData[idx]);
                    }

                    if (toRegister.length === 0) {
                        alert('No servers selected.');
                        return;
                    }

                    fetch('/api/discover/register', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(toRegister)
                    })
                        .then(function (res) { return res.json(); })
                        .then(function (registered) {
                            discoverResults.innerHTML = '<p class="empty-message">Registered ' + registered.length + ' server(s).</p>';
                            loadServers();
                        })
                        .catch(function (err) { alert('Registration failed: ' + err); });
                });
            })
            .catch(function (err) {
                discoverBtn.disabled = false;
                discoverBtn.textContent = 'Discover';
                discoverStatus.textContent = 'Error: ' + err;
            });
    });

    // --- Refresh ---
    document.getElementById('refresh-btn').addEventListener('click', function () {
        loadServers();
    });

    // Auto-refresh
    loadServers();
    refreshTimer = setInterval(loadServers, REFRESH_INTERVAL);
})();
