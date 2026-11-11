
# React 빌드
FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .

ARG VITE_API_MOCK_AUTH=false
ARG VITE_BFF_TASKS_ENABLED=false

ENV VITE_API_MOCK_AUTH=${VITE_API_MOCK_AUTH}
ENV VITE_BFF_TASKS_ENABLED=${VITE_BFF_TASKS_ENABLED}

RUN npm run build

# Nginx로 정적 파일 제공
FROM nginx:stable-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

