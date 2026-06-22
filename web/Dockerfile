# Static web tier: nginx + baked-in assets so the image is portable
# (no bind-mounts required for Kubernetes / remote-server deploys).
FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY html/ /usr/share/nginx/html/

EXPOSE 80
