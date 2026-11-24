import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const accepted = new Counter('accepted_200');
const rejected = new Counter('rejected_429');
const unexpected = new Counter('unexpected_responses');
const acceptedLatency = new Trend('accepted_latency', true);

const rate = Number(__ENV.RATE || '1');
const duration = __ENV.DURATION || '30s';
const baseUrl = __ENV.BASE_URL || 'http://localhost:18088';

export const options = {
  scenarios: {
    traffic: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      preAllocatedVUs: 20,
      maxVUs: 60,
    },
  },
  discardResponseBodies: true,
};

export default function () {
  const response = http.get(`${baseUrl}/api/movies`);

  if (response.status === 200) {
    accepted.add(1);
    acceptedLatency.add(response.timings.duration);
  } else if (response.status === 429) {
    rejected.add(1);
  } else {
    unexpected.add(1);
    console.error(`Unexpected HTTP status: ${response.status}`);
  }
}
