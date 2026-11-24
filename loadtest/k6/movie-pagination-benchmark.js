import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import {
  LOAD,
  arrivalRate,
  handleSummary,
  summaryOptions
} from './lib/summary.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const MODE = __ENV.MODE || 'after';

const responseBytes = new Trend('movie_response_bytes');

export const options = {
  ...summaryOptions,
  scenarios: {
    moviePagination: arrivalRate()
  }
};

export function setup() {
  if (!['before', 'after'].includes(MODE)) {
    throw new Error('MODE must be before or after');
  }

  const response = http.get(`${BASE_URL}/api/movies`);

  if (response.status !== 200) {
    throw new Error(`API preflight failed: HTTP ${response.status}`);
  }

  const movies = response.json();

  if (MODE === 'after') {
    if (movies.length !== 20) {
      throw new Error(`Expected 20 movies, got ${movies.length}`);
    }

    if (response.headers['X-Total-Count'] !== '10000') {
      throw new Error('Expected X-Total-Count: 10000');
    }
  } else {
    if (movies.length !== 10000) {
      throw new Error(`Expected 10000 movies, got ${movies.length}`);
    }
  }

  return {};
}

export default function () {
  const response = http.get(`${BASE_URL}/api/movies`, LOAD);

  check(response, {
    'movie list status 200': (r) => r.status === 200,
    'movie list has body': (r) => r.body && r.body.length > 0
  });

  if (response.status === 200) {
    // The benchmark seed uses ASCII text.
    responseBytes.add(response.body.length, { phase: 'load' });
  }
}
