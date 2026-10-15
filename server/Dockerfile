FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY server.js ./
COPY public ./public
ENV PORT=8080
VOLUME /app/data
EXPOSE 8080
CMD ["node", "server.js"]
