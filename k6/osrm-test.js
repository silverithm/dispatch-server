import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '30s', target: 100 },   // 빠른 램프업
        { duration: '1m', target: 2000 },    // 부하 증가
        { duration: '2m', target: 5000 },    // 피크 부하
        { duration: '1m', target: 2000 },    // 감소
        { duration: '30s', target: 0 }      // 정리
    ],
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        http_req_failed: ['rate<0.01'],
    },
};

const COORDINATES = [
    '128.0923,35.1747;128.1171,35.1759',
    '128.1171,35.1759;128.0923,35.1747',
    '128.0923,35.1747;128.0923,35.1759',
    '128.1171,35.1747;128.1171,35.1759'
];

export default function() {
    const baseUrl = 'http://1.230.54.14:5001';
    const coordinates = COORDINATES[Math.floor(Math.random() * COORDINATES.length)];
    const url = `${baseUrl}/route/v1/driving/${coordinates}`;

    const startTime = new Date().getTime();

    const params = {
        timeout: '10s',
        headers: { 'Content-Type': 'application/json' },
    };

    const response = http.get(url, params);

    check(response, {
        'status is 200': (r) => r.status === 200,
        'response body has routes': (r) => r.json('routes') !== undefined,
        'response time OK': (r) => r.timings.duration < 2000,
    });

    if (response.status !== 200) {
        console.error(`Error: ${response.status}, Body: ${response.body}`);
    }

    const elapsedTime = new Date().getTime() - startTime;
    const sleepTime = Math.max(0, 1000 - elapsedTime);
    sleep(sleepTime / 1000);
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        './summary.json': JSON.stringify(data),
    };
}

export function setup() {
    return {
        baseUrl: __ENV.BASE_URL || 'http://1.230.54.14:5001',
    };
}