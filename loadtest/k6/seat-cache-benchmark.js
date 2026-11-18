
import http from 'k6/http';
import { check } from 'k6';
import {
  LOAD, arrivalRate, handleSummary, summaryOptions
} from './lib/summary.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  ...summaryOptions,
  scenarios: {
    seatCache: arrivalRate()
  }
};

export function setup() {
  // Find a seeded showtime for today or tomorrow
  for (const offset of [0, 1]) {
    const date = new Date(
      Date.now() + (7 + offset * 24) * 3600000
    ).toISOString().slice(0, 10);

    const response = http.get(
      `${BASE_URL}/api/showtimes?date=${date}`
    );

    if (response.status !== 200) {
      throw new Error(`Showtime lookup failed: ${response.status}`);
    }

    const showtimes = response.json();

    if (showtimes.length > 0) {
      return { showtimeId: showtimes[0].id };
    }
  }

  throw new Error('No seeded showtime found');
}

export default function (data) {
  const response = http.get(
    `${BASE_URL}/api/showtimes/${data.showtimeId}/seats`,
    LOAD
  );

  check(response, {
    'seat map status 200': (r) => r.status === 200,
    'seat map has 120 seats': (r) =>
      r.status === 200 && r.json().length === 120
  });
}
