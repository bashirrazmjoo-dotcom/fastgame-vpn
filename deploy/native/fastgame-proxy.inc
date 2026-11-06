proxy_pass http://127.0.0.1:18080;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $fg_client_ip;
proxy_set_header X-Forwarded-Proto https;
