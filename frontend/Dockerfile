FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# npm ci rejects a lockfile npm itself just generated for this tree (npm 10 bug), so install against the
# committed lockfile instead. Slightly less strict than ci: a drifted lockfile is updated rather than rejected.
RUN npm install --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/lodge/browser /usr/share/nginx/html
EXPOSE 80
