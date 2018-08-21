[![Build Status](https://travis-ci.org/ReadyTalk/stuncheck.svg?branch=master)](https://travis-ci.org/ReadyTalk/stuncheck)

# StunCheck

StunCheck is a simple healthcheck for stun servers.  It monitors that stun servers are up and working and reports the stats for them in a simple HTTP request.  The goal of this project is to have a service that makes it easy/simple to know when stun servers are having issues and allow action to be taken.  Whether its pulling the bad server out of DNS or triggering a restart or alert around it.

Most heath check systems use simple rest/http endpoints to figure out if something is healthy this just enables the ability to do that for older legacy STUN service that dont have an http healthcheck.

## Config
Each config paramiter can be used on the command line (first option) or as an environment variable.  Anything passed in as part of the command will override environment variables for that same option.

* __--stun_servers__:__STUN_SERVERS__: This is required, it allows you to specify the servers to connect to.  It takes a comma seperated list of servers, they can use either domain or IP and can also be given a port, if no port is provided the default (3478) will be used.
  * __Example__:  ```--stun_servers 192.168.1.1:8899,stun.test.com:2233```
  * __Default__: None, this is a required field.
* __--listen_address__:__STUN_LISTEN_ADDRESS__: This is the IP and port to have the HTTP server listen on.  This will also be the IP address to send stun requests from.  Currently there is no way to sperate these 2 things.
  * __Example: --listen_address 0.0.0.0:8080
  * __Default__: None, this is a required field.
* __--delay__:__STUN_DELAY__: The delay in seconds between checks.  This can be anywhere between 120 and 1 seconds.
  *  __Example__: --delay 15
  * __Default__: 5
* __--max_latency__:__STUN_MAX_LATENCY__:  The maxium the average latency can be before  falling the healthcheck.
  *  __Example__: --max_latency 50
  * __Default__: 100
* __--maxFailurePCT:__STUN_FAILPCT__:  The highest the failure percentage can get to before the healthcheck is considered failed.  This is expressed as a percentage, so .10 is 10% 1.0 would be 100%.
  *  __Example__: --maxFailurePCT = .3
  * __Default__: .10
* __--cachedResults:__STUN_CACHED_RESULTS__:  The number of results to use for the current stats.  We only use these for the health check.  Total stats are kept, but not used to determine failure stats.  This can be anywhere between 10-10000.
  *  __Example__: --cachedResults = .3
  * __Default__: .10
