# 🎓 Student Management System — Microservices Backend

[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-Eureka_%26_Gateway-6DB33F?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-cloud)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-Avro_Events-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Multi--Database-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![MinIO](https://img.shields.io/badge/MinIO-Object_Storage-C72C48?style=for-the-badge&logo=minio&logoColor=white)](https://min.io/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-Circuit_Breaker-FF5722?style=for-the-badge)](https://resilience4j.readme.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose_Enabled-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

A comprehensive, distributed **Microservices-based Learning and Student Management System (LMS & SMS)** built with **Spring Cloud, Apache Kafka (Avro Schema Registry), MinIO S3 Object Storage, multi-tenant PostgreSQL databases**, and a centralized observability stack (**Prometheus, Tempo, Loki4j**).

---

## 📑 Table of Contents
- [Architecture Overview & System Topology](#-architecture-overview--system-topology)
- [Service Matrix & Port Mapping](#-service-matrix--port-mapping)
- [Core Tech Stack](#-core-tech-stack)
- [Monorepo Project Structure](#-monorepo-project-structure)
- [Microservices Breakdown](#-microservices-breakdown)
  - [1. Gateway Service](#1-gateway-service-port-8080)
  - [2. Discovery Service](#2-discovery-service-port-8761)
  - [3. User Service](#3-user-service-port-8081)
  - [4. Class Service](#4-class-service-port-8082)
  - [5. Assignment Service](#5-assignment-service-port-8083)
  - [6. Notification Service](#6-notification-service-port-8084)
- [Inter-Service Communication](#-inter-service-communication)
- [Getting Started](#-getting-started)
  - [Prerequisites](#prerequisites)
  - [Running with Docker Compose (Recommended)](#running-with-docker-compose-recommended)
  - [Local Development Setup](#local-development-setup)
- [CI/CD & Deployment Pipeline](#-cicd--deployment-pipeline)
- [Security & Technical Best Practices](#-security--technical-best-practices)

---

## 🏗 Architecture Overview & System Topology

```text
                                  +-----------------------+
                                  |   React / Web Client  |
                                  +-----------+-----------+
                                              |
                                              | HTTP / REST (JWT Bearer)
                                              v
                                  +-----------------------+
                                  |    API Gateway (8080) |
                                  +-----------+-----------+
                                              |
                 +----------------------------+----------------------------+
                 |                            |                            |
                 v                            v                            v
      +---------------------+      +---------------------+      +---------------------+
      |  User Service (8081)|      | Class Service (8082)|      |Assignment Svc (8083)|
      +----------+----------+      +----------+----------+      +----------+----------+
                 |                            |                            |
                 | WebClient (Resilience4j)   | WebClient (Resilience4j)   | Kafka Event (Avro)
                 +----------------------------+                            |
                 |                                                         |
                 v                                                         v
      +---------------------+                                   +---------------------+
      | Discovery (8761)    |                                   |  Kafka Topic (9092) |
      | (Netflix Eureka)    |                                   +----------+----------+
      +---------------------+                                              |
                                                                           v
                                                                +---------------------+
                                                                |Notification Svc(8084|
                                                                +----------+----------+
                                                                           |
                                                                 SMTP Mail | / DB Save
                                                                           v
                                                                   [ Student/Teacher ]
```

---

## 🔌 Service Matrix & Port Mapping

| Service | Port | Database / Storage | Primary Responsibilities |
| :--- | :--- | :--- | :--- |
| **API Gateway** | `8080` | — | Single entry point, load-balanced dynamic routing, centralized CORS & header deduplication |
| **Discovery Service** | `8761` | — | Service registry and heartbeat health monitoring powered by Netflix Eureka Server |
| **User Service** | `8081` | PostgreSQL (`user_service`) | JWT Auth (Access/Refresh rotation), 3-tier RBAC (`STUDENT`, `TEACHER`, `ADMIN`), Email OTP Password Reset |
| **Class Service** | `8082` | PostgreSQL (`class_service`), MinIO (`classroom-materials`) | Class, Course, Semester, and Member management, random class code generation, MinIO course materials storage |
| **Assignment Service** | `8083` | PostgreSQL (`assignment_service`), MinIO (`assignment-materials`) | Assignment lifecycle, multi-file submissions, grading & feedback, matrix gradebook calculation, CSV export |
| **Notification Service**| `8084` | PostgreSQL (`notification_service`) | Kafka Avro consumer, email dispatch via JavaMailSender, OpenFeign inter-service student lookup |
| **Kafka & Schema Registry** | `9092` / `8085` | Confluent Schema Registry | High-throughput asynchronous event bus with binary Avro serialization |
| **MinIO S3 Storage** | `9000` / `9001` | Object Storage Buckets | Secure bucket storage for course materials, assignment prompts, and student submission files |
| **Observability Stack** | `3100`, `9411`, `9090` | Loki, Tempo, Prometheus | End-to-end metrics scraping, distributed tracing (Micrometer/Brave), and centralized logging (Loki4j) |

---

## 🛠 Core Tech Stack

- **Languages & Frameworks**: Java 17, Spring Boot 3.x, Spring Cloud 2023+ (Gateway, Eureka, OpenFeign)
- **Security & Authorization**: Spring Security 6, JJWT (HMAC-SHA256 Token & Refresh Token rotation), Role-Based Access Control
- **Fault Tolerance & Resilience**: Resilience4j Circuit Breakers, Retry Pattern, Fallback methods
- **Event-Driven Messaging**: Spring Kafka, Apache Avro Serializer, Confluent Schema Registry
- **File & Object Storage**: MinIO Java SDK 8.5+ (Presigned download links, multipart file uploads)
- **Databases & ORM**: Spring Data JPA, Hibernate, PostgreSQL (Database-per-service pattern)
- **Observability & Tracing**: Micrometer Observation, Prometheus Metrics, Grafana Tempo (Zipkin endpoint), Loki4j log appender

---

## 📂 Monorepo Project Structure

```text
.
├── .github/workflows/
│   └── user-service-cicd.yml    # Parallel Docker build & Docker Compose deployment pipeline
├── assignment_service/          # Assignment, submission, grading logic & Kafka event producer
├── class_service/               # Classroom, course, semester, and MinIO material service
├── discovery_service/           # Netflix Eureka discovery server
├── gateway_service/             # Spring Cloud Gateway routing & CORS configuration
├── notification_service/        # Kafka consumer, OpenFeign client & JavaMailSender dispatcher
├── user-service/                # Auth, profile, RBAC, Admin CRUD & Email OTP verification
├── datasources.yml              # Centralized datasource configurations
├── docker-compose.yml           # Multi-container orchestration specification
├── prometheus.yml               # Prometheus metric scraping rules
├── tempo.yml                    # Grafana Tempo distributed tracing configuration
└── pom.xml                      # Parent POM managing shared dependencies & plugins
```

---

## 🔍 Microservices Breakdown

### 1. Gateway Service (Port `8080`)
- Intercepts all incoming client requests under `/api/v1/**`.
- Enforces global CORS rules and strips duplicate CORS response headers (`DedupeResponseHeader`).
- Dynamic routing via Eureka service IDs:
  - `/api/v1/classes/**`, `/api/v1/courses/**`, `/api/v1/semesters/**` ➔ `lb://class-service`
  - `/api/v1/auth/**`, `/api/v1/admin/**`, `/api/v1/teacher/**`, `/api/v1/student/**` ➔ `lb://user-service`
  - `/api/v1/assignments/**` ➔ `lb://assignment-service`
  - `/api/v1/notifications/**` ➔ `lb://notification-service`

### 2. Discovery Service (Port `8761`)
- Acts as the central Service Registry.
- Visual service health and instance status dashboard accessible at: `http://localhost:8761`

### 3. User Service (Port `8081`)
- **Authentication**:
  - `POST /api/v1/auth/student/register`: Register student accounts.
  - `POST /api/v1/auth/{student|teacher|admin}/login`: Role-specific authentication issuing JWT and Refresh Tokens.
  - `POST /api/v1/auth/refresh-token`: Exchange valid refresh tokens for refreshed access tokens.
  - `POST /api/v1/auth/student/forgot-password` & `verify-otp`: Password recovery via time-limited 6-digit email OTPs.
- **Admin Management**:
  - `GET /api/v1/admin/users`: Query user directory filtered by Role, Major, Class, or keyword search.
  - `POST /api/v1/admin/users`: Create user accounts.
  - `PATCH /api/v1/admin/users/{id}/toggle-status`: Instant account lock/unlock (`ACTIVE` / `LOCKED`).
  - `PATCH /api/v1/admin/users/{id}/reset-password`: Set temporary credentials.
- **Profile Management**: Profile updates and password changes.

### 4. Class Service (Port `8082`)
- **Teacher Class Operations**:
  - `POST /api/v1/classes/teacher/create`: Create classes with auto-generated 6-character random codes and optional join passwords.
  - `POST /api/v1/classes/teacher/{classId}/materials/upload`: Upload course materials to MinIO.
  - `POST /api/v1/classes/teacher/{classId}/announcements`: Publish classroom announcements.
- **Student Class Operations**:
  - `POST /api/v1/classes/student/join`: Enroll in a class using code and password.
  - `GET /api/v1/classes/student/all`: Retrieve all enrolled classes.
  - `GET /api/v1/classes/materials/{fileId}/download`: Generate presigned download links (15-minute expiration).
- **Inter-service Resilience**: Fetches user details from `user-service` using WebClient guarded by Resilience4j Circuit Breakers and Fallbacks.

### 5. Assignment Service (Port `8083`)
- **Teacher Assignment API**:
  - `POST /api/v1/assignments/teacher/create`: Create assignments with attachments. Dispatches `ASSIGNMENT_CREATED` event to Kafka `notification-events-topic`.
  - `PUT /api/v1/assignments/teacher/submissions/{id}/grade`: Grade submissions and provide feedback. Dispatches `GRADE_UPDATED` event to Kafka.
  - `GET /api/v1/assignments/gradebook/class/{classId}`: Matrix gradebook calculation with student grade rows and average scores.
- **Student Submission API**:
  - `POST /api/v1/assignments/student/{id}/submit`: Upload multiple submission files; automatically tags `ON_TIME` or `LATE`. Emits `SUBMISSION_CREATED` event.
  - `DELETE /api/v1/assignments/student/{id}/unsubmit`: Recall and delete submission files from MinIO (allowed only before grading).
  - `GET /api/v1/assignments/student/dashboard-stats`: Calculate 4.0 GPA, assignment completion rates, and academic standing.

### 6. Notification Service (Port `8084`)
- **Kafka Event Consumer**: Consumes messages from `notification-events-topic` matching Avro schema (`notification-event.avsc`).
- **OpenFeign Integration**: Fetches enrolled student emails from `class-service` when class-wide events are triggered.
- **Email Dispatcher**: Sends formatted notification emails via SMTP Gmail (`JavaMailSender`).
- **In-App Notification API**: Query unread counts and mark notifications as read (`/api/v1/notifications`).

---

## ⚡ Inter-Service Communication

```text
┌────────────────────┐   HTTP WebClient (Resilience4j)   ┌────────────────────┐
│   class_service    │ ────────────────────────────────► │    user-service    │
└────────────────────┘                                   └────────────────────┘
          │
          │ Kafka Event (Avro Binary)
          ▼
┌────────────────────┐          OpenFeign Call           ┌────────────────────┐
│assignment_service  │ ────────────────────────────────► │notification_service│
└────────────────────┘                                   └────────────────────┘
```

1. **Synchronous Communication**: Spring WebClient annotated with `@LoadBalanced` resolves target service instances dynamically through Eureka. Resilience4j Circuit Breakers (`slidingWindowSize=5`, `failureRateThreshold=50%`, `waitDurationInOpenState=60s`) and Fallbacks protect services from cascading failures.
2. **Asynchronous Communication**: Apache Kafka with Confluent Schema Registry decouples email notifications from core transactional flows, eliminating submission and grading latency.

---

## 🚀 Getting Started

### Prerequisites
- **Docker** & **Docker Compose**
- **JDK 17** & **Maven 3.9+** (if running services individually)
- Required Ports: `8080, 8081, 8082, 8083, 8084, 8761, 9000, 9001, 9092, 5432`

### Running with Docker Compose (Recommended)

1. **Clone the repository:**
   ```bash
   git clone https://github.com/PhilipTran-Dev/Student_ManagementBE.git
   cd Student_ManagementBE
   ```

2. **Build and start all services:**
   ```bash
   docker compose up -d --build
   ```

3. **Verify container health:**
   ```bash
   docker compose ps
   ```

---

## 🔄 CI/CD & Deployment Pipeline

Configured in `.github/workflows/user-service-cicd.yml` for pushes to `main` and `master`:
- **Stage 1 (Parallel Builds)**: Concurrently builds 6 Docker images using GitHub Actions caching (`gha`) and pushes them to Docker Hub (`phucngo249/*`).
- **Stage 2 (Automated Deployment)**: Triggers on a self-hosted runner, pulling new images and executing `docker compose up -d --force-recreate` for seamless deployment.

---

## ⚠️ Security & Technical Best Practices

1. **Externalize Credentials**: Migrate plain-text SMTP passwords (`fzampoobgvvflaed`) and MinIO credentials in `application.yml` files to environment variables (`SPRING_MAIL_PASSWORD`, `MINIO_SECRET_KEY`) for production environments.
2. **Unified JWT Secrets**: Ensure all microservices (`user`, `class`, `assignment`) share a synchronized `APP_JWT_SECRET` via Spring Cloud Config or environment variables to correctly sign and verify user tokens.
