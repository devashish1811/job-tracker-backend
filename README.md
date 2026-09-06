# Job Tracker Backend

Spring Boot REST API for Job Tracker application.

## Running Locally

### Prerequisites
- Java 8+
- PostgreSQL running locally
- Maven 3.6+

### Setup
1. Create a PostgreSQL database named `jobtracker`
2. Update `src/main/resources/application.properties` or set env vars:
   - `DB_URL` = `jdbc:postgresql://localhost:5432/jobtracker`
   - `DB_USERNAME` = `postgres`
   - `DB_PASSWORD` = your password
   - `JWT_SECRET` = any long secret string

### Run
```bash
mvn spring-boot:run
```

API will start on http://localhost:8080

## Endpoints
- POST /api/auth/register
- POST /api/auth/login
- GET  /api/jobs
- POST /api/jobs/extract
- POST /api/jobs/manual
- PUT  /api/jobs/{id}
- DELETE /api/jobs/{id}
- GET  /api/jobs/search?q=query

## Deploy to Railway
1. Push this folder to a GitHub repo
2. Connect repo to Railway.app
3. Set environment variables in Railway dashboard
4. Railway auto-detects Dockerfile and deploys
