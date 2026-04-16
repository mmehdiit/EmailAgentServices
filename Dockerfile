# Use a specific version instead of generic slim
FROM eclipse-temurin:25-jre-jammy

# Set timezone earlier as it's an environment variable
ENV TZ=Asia/Beirut

# Ensure the system is updated and install necessary libraries
RUN apt-get update && \
    apt-get install -y libfreetype6 fontconfig && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user
RUN groupadd -r javauser && useradd -r -g javauser javauser

# Create and set proper directory
WORKDIR /app

# Copy the JAR file with specific ownership
ARG JAR_FILE=../target/*.jar
COPY ${JAR_FILE} EmailAgentServices.jar
RUN chown -R javauser:javauser /app

# Switch to non-root user
USER javauser

# Add Java runtime options for better container support
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75", \
    "-jar", "/app/EmailAgentServices.jar"]