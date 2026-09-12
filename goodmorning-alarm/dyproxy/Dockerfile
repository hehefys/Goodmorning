FROM node:24-alpine
WORKDIR /app
COPY dyproxy.cjs /app/dyproxy.cjs
ENV PORT=1200
EXPOSE 1200
CMD ["node", "/app/dyproxy.cjs"]
