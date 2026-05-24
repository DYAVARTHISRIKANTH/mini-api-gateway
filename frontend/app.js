document.addEventListener('DOMContentLoaded', () => {
    // DOM Elements
    const clientIpSelect = document.getElementById('client-ip-select');
    const customIpInput = document.getElementById('custom-ip-input');
    const btnTest = document.getElementById('btn-test');
    const btnHealth = document.getElementById('btn-health');
    const btnStatus = document.getElementById('btn-status');
    const btnBurst = document.getElementById('btn-burst');
    const burstProgressBar = document.getElementById('burst-progress');
    const burstProgressContainer = document.querySelector('.burst-progress-container');
    
    const statusBadge = document.getElementById('status-badge');
    const responseTimeLabel = document.getElementById('response-time');
    const headerCorrelationId = document.getElementById('header-correlation-id');
    const headerRetryAfter = document.getElementById('header-retry-after');
    const jsonResponseCode = document.getElementById('json-response-code');
    const btnClearResponse = document.getElementById('btn-clear-response');
    
    const terminalBody = document.getElementById('terminal-body');
    const btnClearLogs = document.getElementById('btn-clear-logs');
    
    const metricTotal = document.getElementById('metric-total-requests');
    const metricSuccess = document.getElementById('metric-success-requests');
    const metricBlocked = document.getElementById('metric-blocked-requests');
    
    const cooldownContainer = document.getElementById('cooldown-container');
    const cooldownTimerVal = document.getElementById('cooldown-timer-val');
    const retryCountdown = document.getElementById('retry-countdown');

    // Local Metrics State
    let totalRequests = 0;
    let successRequests = 0;
    let blockedRequests = 0;
    let cooldownInterval = null;

    // Toggle Custom IP Input
    clientIpSelect.addEventListener('change', () => {
        if (clientIpSelect.value === 'custom') {
            customIpInput.classList.remove('hidden');
        } else {
            customIpInput.classList.add('hidden');
        }
    });

    // Get Active IP Address
    function getClientIp() {
        if (clientIpSelect.value === 'custom') {
            return customIpInput.value.trim() || '127.0.0.1';
        }
        return clientIpSelect.value;
    }

    // Append custom lines to terminal logs
    function logToTerminal(message, type = 'info') {
        const timestamp = new Date().toISOString().split('T')[1].slice(0, 12);
        const logLine = document.createElement('div');
        logLine.className = `log-line log-${type}`;
        logLine.textContent = `[${timestamp}] ${message}`;
        terminalBody.appendChild(logLine);
        terminalBody.scrollTop = terminalBody.scrollHeight;
    }

    // Update Local Metrics Cards
    function updateMetrics(status) {
        totalRequests++;
        metricTotal.textContent = totalRequests;

        if (status === 200) {
            successRequests++;
            metricSuccess.textContent = successRequests;
        } else if (status === 429) {
            blockedRequests++;
            metricBlocked.textContent = blockedRequests;
        }
    }

    // Trigger Cooldown Countdown Timer on 429
    function startCooldown(seconds) {
        if (cooldownInterval) {
            clearInterval(cooldownInterval);
        }

        cooldownContainer.classList.remove('hidden');
        let remaining = seconds;
        cooldownTimerVal.textContent = remaining;
        retryCountdown.textContent = remaining;

        cooldownInterval = setInterval(() => {
            remaining--;
            cooldownTimerVal.textContent = remaining;
            retryCountdown.textContent = remaining;

            if (remaining <= 0) {
                clearInterval(cooldownInterval);
                cooldownContainer.classList.add('hidden');
                logToTerminal("Sliding window cooldown finished. Limit reset.", "success");
            }
        }, 1000);
    }

    // Single Request Pipeline
    async function sendRequest(endpoint) {
        const clientIp = getClientIp();
        const startTime = performance.now();
        const targetUrl = `http://localhost:8080${endpoint}`;

        logToTerminal(`Dispatching GET ${endpoint} | Simulated IP: ${clientIp}`, "trace");

        // UI Updates for Pending State
        statusBadge.className = 'status-indicator status-idle';
        statusBadge.textContent = 'PENDING';
        responseTimeLabel.textContent = 'calculating...';

        try {
            const response = await fetch(targetUrl, {
                method: 'GET',
                headers: {
                    'X-Forwarded-For': clientIp
                }
            });

            const latency = Math.round(performance.now() - startTime);
            const correlationId = response.headers.get('X-Correlation-Id') || 'N/A';
            const retryAfter = response.headers.get('Retry-After');

            // Log responses & headers
            headerCorrelationId.textContent = correlationId;
            headerRetryAfter.textContent = retryAfter || '-';
            responseTimeLabel.textContent = `${latency} ms`;
            updateMetrics(response.status);

            const json = await response.json();
            jsonResponseCode.textContent = JSON.stringify(json, null, 2);

            if (response.status === 200) {
                statusBadge.className = 'status-indicator status-success';
                statusBadge.textContent = '200 OK';
                logToTerminal(`Response 200 OK | CorrID: ${correlationId} | Latency: ${latency}ms`, "success");
            } else if (response.status === 429) {
                statusBadge.className = 'status-indicator status-throttled';
                statusBadge.textContent = '429 TOO MANY REQUESTS';
                logToTerminal(`Response 429 Too Many Requests | Retry-After: ${retryAfter}s | CorrID: ${correlationId}`, "error");
                
                if (retryAfter) {
                    startCooldown(parseInt(retryAfter));
                }
            } else {
                statusBadge.className = 'status-indicator status-throttled';
                statusBadge.textContent = `${response.status} ERROR`;
                logToTerminal(`Response ${response.status} | CorrID: ${correlationId}`, "error");
            }

        } catch (error) {
            const latency = Math.round(performance.now() - startTime);
            statusBadge.className = 'status-indicator status-throttled';
            statusBadge.textContent = 'GATEWAY UNREACHABLE';
            responseTimeLabel.textContent = `${latency} ms`;
            jsonResponseCode.textContent = JSON.stringify({
                error: "Connection refused",
                message: "Could not connect to the API Gateway backend. Please verify that the Spring Boot application is running on port 8080.",
                hint: "Run 'mvn spring-boot:run' to start the server."
            }, null, 2);

            logToTerminal(`Connection Refused on GET ${endpoint} | Verify Gateway port 8080`, "error");
        }
    }

    // Launch Sequential traffic burst (12 requests)
    async function simulateBurst() {
        btnBurst.disabled = true;
        btnBurst.classList.add('btn-secondary');
        burstProgressContainer.classList.remove('hidden');
        burstProgressBar.style.width = '0%';
        
        const clientIp = getClientIp();
        logToTerminal(`Initializing Traffic Burst of 12 requests for client IP: ${clientIp}...`, "warn");

        const requests = [];
        const burstCount = 12;

        for (let i = 1; i <= burstCount; i++) {
            // Slight delay (50ms) to ensure timestamps are registered sequentially, demonstrating sliding window filling up
            await new Promise(r => setTimeout(r, 50));
            
            const targetUrl = `http://localhost:8080/api/test`;
            const startTime = performance.now();

            const reqPromise = fetch(targetUrl, {
                method: 'GET',
                headers: {
                    'X-Forwarded-For': clientIp
                }
            }).then(async (response) => {
                const latency = Math.round(performance.now() - startTime);
                const correlationId = response.headers.get('X-Correlation-Id') || 'N/A';
                const retryAfter = response.headers.get('Retry-After');
                
                updateMetrics(response.status);

                const percent = Math.round((i / burstCount) * 100);
                burstProgressBar.style.width = `${percent}%`;

                if (response.status === 200) {
                    logToTerminal(`[Burst #${i}] 200 OK | CorrID: ${correlationId} | Latency: ${latency}ms`, "success");
                } else if (response.status === 429) {
                    logToTerminal(`[Burst #${i}] 429 Blocked | Retry-After: ${retryAfter}s | CorrID: ${correlationId}`, "error");
                    if (i === burstCount) {
                        // Render final response details on screen
                        statusBadge.className = 'status-indicator status-throttled';
                        statusBadge.textContent = '429 TOO MANY REQUESTS';
                        responseTimeLabel.textContent = `${latency} ms`;
                        headerCorrelationId.textContent = correlationId;
                        headerRetryAfter.textContent = retryAfter || '-';
                        
                        const json = await response.json();
                        jsonResponseCode.textContent = JSON.stringify(json, null, 2);
                        
                        if (retryAfter) {
                            startCooldown(parseInt(retryAfter));
                        }
                    }
                }
            }).catch(err => {
                logToTerminal(`[Burst #${i}] Connection failed`, "error");
            });

            requests.push(reqPromise);
        }

        await Promise.all(requests);
        logToTerminal("Burst simulation cycle finalized.", "info");
        
        setTimeout(() => {
            btnBurst.disabled = false;
            btnBurst.classList.remove('btn-secondary');
            burstProgressContainer.classList.add('hidden');
        }, 1000);
    }

    // Attach Event Listeners
    btnTest.addEventListener('click', () => sendRequest('/api/test'));
    btnHealth.addEventListener('click', () => sendRequest('/api/health'));
    btnStatus.addEventListener('click', () => sendRequest('/api/status'));
    btnBurst.addEventListener('click', simulateBurst);

    btnClearResponse.addEventListener('click', () => {
        jsonResponseCode.textContent = '{}';
        statusBadge.className = 'status-indicator status-idle';
        statusBadge.textContent = 'Idle';
        responseTimeLabel.textContent = '';
        headerCorrelationId.textContent = '-';
        headerRetryAfter.textContent = '-';
    });

    btnClearLogs.addEventListener('click', () => {
        terminalBody.innerHTML = '';
        logToTerminal("Live console logs cleared.", "info");
    });
});
