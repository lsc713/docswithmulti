# 부하 실측용 서비스 이미지 (멀티스테이지, 4개 서비스 공용)
#   build context = repo root
#   dockerfile    = infra/load-test/deploy/Dockerfile
#   ARG MODULE    = payment-service | risk-management-service | merchant-limit-service | order-service
# 예) docker compose -f payment.compose.yml up -d --build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN ./gradlew :${MODULE}:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/build/libs/*.jar /app/app.jar
# 서비스별 포트는 compose 에서 host network 로 노출
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
