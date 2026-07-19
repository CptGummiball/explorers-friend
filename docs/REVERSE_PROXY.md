# Reverse proxy guide

Keep `web.bind` at `127.0.0.1` and let a proxy handle TLS, caching and (optionally)
authentication. Set `web.public-base-url` so `/efmap web status` prints the right link.

## nginx

```nginx
server {
    listen 443 ssl;
    server_name map.example.org;
    # ssl_certificate ...; ssl_certificate_key ...;

    location / {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # tiles revalidate via ETag; let the proxy cache 304 cheaply
        proxy_cache_revalidate on;
        # optional basic auth:
        # auth_basic "Map";
        # auth_basic_user_file /etc/nginx/map.htpasswd;
    }
}
```

Sub-path deployments (`https://example.org/map/`) are not supported in 0.1.0 — use a
dedicated (sub)domain. The frontend uses absolute paths (`/tiles/...`, `/api/...`).

## Caddy

```caddy
map.example.org {
    reverse_proxy 127.0.0.1:8080
    # basic_auth { user $2a$14$... }
}
```

## Apache httpd

```apache
<VirtualHost *:443>
    ServerName map.example.org
    ProxyPreserveHost On
    ProxyPass        / http://127.0.0.1:8080/
    ProxyPassReverse / http://127.0.0.1:8080/
</VirtualHost>
```

Config on the mod side:

```jsonc
"web": {
  "bind": "127.0.0.1",
  "port": 8080,
  "public-base-url": "https://map.example.org/"
}
```
