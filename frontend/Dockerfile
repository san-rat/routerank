# Build stage
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Local stack default: nginx serves data/downloads at /tiles (see infra/docker-compose.yml)
ARG VITE_TILES_URL=/tiles/sri-lanka-2026-10-02.pmtiles
ENV VITE_TILES_URL=$VITE_TILES_URL
RUN npm run build

# Serve stage
FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
