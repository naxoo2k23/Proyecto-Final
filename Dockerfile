FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY lib ./lib
COPY src ./src
RUN mkdir -p bin && find src -name "*.java" > sources.txt && javac -encoding UTF-8 -cp "lib/*" -d bin @sources.txt

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/bin ./bin
COPY lib ./lib
COPY database ./database
COPY public ./public
RUN mkdir -p /app/data
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "bin:lib/*", "com.playmatch.ServidorPlayMatch"]
