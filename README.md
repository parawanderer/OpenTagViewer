# OpenTagViewer

My **Heavily WIP** attempt at making AirTag tracking available via a Map-based UI (like that of iOS FindMy or Samsung SmartThings) on Android via this great project: **[FindMy.py](https://github.com/malmeloo/FindMy.py)**

Android (Java) + Python via Chaquopy

Currently this is not really usable yet due to lack of features & polish (it only shows the icons on the map and refreshes them in specific scenarios or on the manual refresh button click).

In the future it might become practically usable, in which case I will do my best to make it easily available/installable.

### Sneak-peak current state:

![Sneakpeak 11 March 2025](./docs/11_03_2025_sneakpeak_2.png)


### Credits

UI Icons by Google: https://fonts.google.com/icons?icon.query=warn&icon.set=Material+Icons

Material theme colors by Google: http://material-foundation.github.io?primary=%23F4FEFF&bodyFont=Nunito&displayFont=Nunito+Sans&colorMatch=false

-----------------------

## Sections to be added/moved/etc...:


#### Notes

It would be nice if `fetch_last_reports` in `FindMy.py` could support fetching reports in smaller time ranges than hours. For example, I can cache historical results, but I would be interested in getting recent data only.


#### How I set up my own anisette server

1. Created a (free) UBUNTU VPS instance @ Oracle and gave it a public IPv4 IP
    - VPS specs in my test:
        - OS: `Ubuntu 24.04 Minimal`
        - Memory (GB): `1`
        - vCPUs: `1/2` (Oracle has a weird definition for this, technically I got `1 OCPU` but they claim this is teh same as `2` vCPUs. More [here](https://blogs.oracle.com/cloud-infrastructure/post/vcpu-and-ocpu-pricing-information))
        - Disk: `df -h` says `45GB` (this is probably a HDD, in the UI it is "Block Storage only". This is whatever the default free option is)

2. Pointed a subdomain at it (Setup a new DNS `A` record to the VPS).
    - I personally use [Cloudflare's Nameservers/DNS manager](https://developers.cloudflare.com/dns/manage-dns-records/how-to/create-dns-records/) because I set this up ages ago. I think you can still use this for free these days.
3. Installed docker on the VPS (follow [this](https://docs.docker.com/engine/install/ubuntu/) official guide up until the `Hello World` test)
4. Follow the "[Server Setup](https://github.com/dchristl/macless-haystack?tab=readme-ov-file#server-setup)" step from the [macless-haystack project](https://github.com/dchristl/macless-haystack?tab=readme-ov-file#server-setup)
    1. Setup a new docker network:
        ```bash
        sudo docker network create mh-network
        ```
    2. Setup the server, mapping the port to your custom port (I chose to expose the server on port `3000` during my testing, feel free to set a different `PORT=<yourport>` value):
        ```bash
        PORT=3000
        sudo docker run -d --restart always \
            --name anisette \
            -p $PORT:6969 \
            --volume anisette-v3_data:/home/Alcoholic/.config/anisette-v3 \
            --network mh-network \
            dadoum/anisette-v3-server
        ```
        If you need HTTP**S**/SSL/TLS, I would suggest making use of Nginx using the [docker-nginx-certbot](https://github.com/JonasAlfredsson/docker-nginx-certbot) project. In this summary I will avoid going any more in-depth on setting up Nginx as a reverse proxy to handle HTTPS traffic.