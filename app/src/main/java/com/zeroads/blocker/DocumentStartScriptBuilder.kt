package com.zeroads.blocker

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DocumentStartScriptBuilder {

    fun buildScript(rules: YouTubeRulesConfig, toggles: FeatureToggles): String {
        val rulesJson = Json.encodeToString(rules)
        val togglesJson = Json.encodeToString(toggles)

        return """
            (function() {
                if (window.ZeroAdsInjected) return;
                window.ZeroAdsInjected = true;
                
                const RULES = $rulesJson;
                const TOGGLES = $togglesJson;
                
                // Strict hostname validation
                const YOUTUBE_DOMAINS = ['youtube.com', 'www.youtube.com', 'm.youtube.com'];
                const IS_YOUTUBE = YOUTUBE_DOMAINS.includes(window.location.hostname);
                
                function reportError(module, error) {
                    if (window.ZeroAdsBridge && IS_YOUTUBE) {
                        window.ZeroAdsBridge.postMessage(JSON.stringify({
                            type: 'MODULE_ERROR',
                            module: module,
                            error: error.message || error.toString()
                        }));
                    }
                }
                
                function notifyBlocked(url) {
                    if (window.ZeroAdsBridge && IS_YOUTUBE) {
                        window.ZeroAdsBridge.postMessage(JSON.stringify({
                            type: 'URL_BLOCKED',
                            url: url
                        }));
                    }
                }
                
                if (IS_YOUTUBE) {
                    // 1. Payload Scrubber (YouTube Only)
                    try {
                        function scrubObject(obj) {
                            if (!obj || typeof obj !== 'object') return;
                            if (Array.isArray(obj)) {
                                obj.forEach(scrubObject);
                                return;
                            }
                            RULES.jsonPruneKeys.forEach(key => {
                                if (obj.hasOwnProperty(key)) {
                                    delete obj[key];
                                    notifyBlocked("Scrubbed JSON key: " + key);
                                }
                            });
                            Object.values(obj).forEach(scrubObject);
                        }
                        
                        const originalParse = JSON.parse;
                        
                        const originalFetch = window.fetch;
                        window.fetch = async function() {
                            const urlArg = arguments[0] instanceof Request ? arguments[0].url : arguments[0];
                            const urlStr = urlArg ? urlArg.toString() : '';
                            const response = await originalFetch.apply(this, arguments);
                            
                            if (urlStr.includes('/youtubei/v1/player') || urlStr.includes('/youtubei/v1/next')) {
                                const cloned = response.clone();
                                response.json = async function() {
                                    const data = await cloned.json();
                                    try { scrubObject(data); } catch(e) {}
                                    return data;
                                };
                            }
                            return response;
                        };
                        
                        const originalXHR = window.XMLHttpRequest;
                        class HookedXHR extends originalXHR {
                            get responseText() {
                                const text = super.responseText;
                                if (this.responseURL && this.responseURL.includes('/youtubei/v1/player')) {
                                    try {
                                        const data = originalParse(text);
                                        scrubObject(data);
                                        return JSON.stringify(data);
                                    } catch(e) {}
                                }
                                return text;
                            }
                            get response() {
                                const res = super.response;
                                if (this.responseType === '' || this.responseType === 'text') {
                                    return this.responseText;
                                }
                                return res;
                            }
                        }
                        window.XMLHttpRequest = HookedXHR;
                        
                    } catch(e) { reportError('PayloadScrubber', e); }
                    
                    // 2. Same-Origin Iframe Patcher
                    try {
                        const originalCreateElement = Document.prototype.createElement;
                        Document.prototype.createElement = function(tagName) {
                            const el = originalCreateElement.apply(this, arguments);
                            if (tagName.toLowerCase() === 'iframe') {
                                el.addEventListener('load', function() {
                                    try {
                                        if (el.contentWindow) {
                                            el.contentWindow.fetch = window.fetch;
                                            el.contentWindow.XMLHttpRequest = window.XMLHttpRequest;
                                        }
                                    } catch(e) {}
                                });
                            }
                            return el;
                        };
                    } catch(e) { reportError('IframePatcher', e); }
                }
                
                // 3. Cosmetic & Feed Cleanup CSS (Generic + YouTube specific)
                try {
                    const cssRules = [];
                    RULES.cosmeticCssSelectors.forEach(selector => {
                        cssRules.push(selector + ' { display: none !important; }');
                    });
                    if (IS_YOUTUBE) {
                        if (TOGGLES.hideShorts && RULES.feedCleanupCss.hideShorts) {
                            cssRules.push(RULES.feedCleanupCss.hideShorts);
                        }
                        if (TOGGLES.hideComments && RULES.feedCleanupCss.hideComments) {
                            cssRules.push(RULES.feedCleanupCss.hideComments);
                        }
                        if (TOGGLES.hideRelated && RULES.feedCleanupCss.hideRelated) {
                            cssRules.push(RULES.feedCleanupCss.hideRelated);
                        }
                    }
                    
                    if (cssRules.length > 0) {
                        const style = document.createElement('style');
                        style.id = 'zeroads-styles';
                        style.textContent = cssRules.join('\n');
                        const injectStyle = () => {
                            if (document.documentElement) {
                                document.documentElement.appendChild(style);
                            } else {
                                setTimeout(injectStyle, 10);
                            }
                        };
                        injectStyle();
                    }
                } catch(e) { reportError('CosmeticCSS', e); }
                
                if (IS_YOUTUBE) {
                    // 4. Ad-Showing Fallback & Skipper
                    try {
                        const observer = new MutationObserver((mutations) => {
                            const player = document.querySelector('#movie_player, .html5-video-player');
                            if (player) {
                                if (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')) {
                                    const video = player.querySelector('video');
                                    if (video) {
                                        video.muted = true;
                                        if (isFinite(video.duration)) {
                                            video.currentTime = video.duration;
                                        }
                                    }
                                    RULES.skipButtonSelectors.forEach(selector => {
                                        const btn = document.querySelector(selector);
                                        if (btn) btn.click();
                                    });
                                }
                            }
                        });
                        observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
                    } catch(e) { reportError('AdSkipper', e); }
                    
                    // 5. Background Playback & Anti-Idle Trick
                    if (TOGGLES.backgroundPlayEnabled) {
                        try {
                            Object.defineProperty(document, 'hidden', { get: () => false });
                            Object.defineProperty(document, 'visibilityState', { get: () => 'visible' });
                            const blockVis = (e) => e.stopImmediatePropagation();
                            document.addEventListener('visibilitychange', blockVis, true);
                            document.addEventListener('webkitvisibilitychange', blockVis, true);
                            window.addEventListener('visibilitychange', blockVis, true);
                            
                            setInterval(() => {
                                if (document.hidden || document.visibilityState !== 'visible') return;
                                document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Shift', keyCode: 16 }));
                            }, 55000);
                        } catch(e) { reportError('BackgroundPlay', e); }
                    }
                    
                    // 6. SponsorBlock Integration
                    if (TOGGLES.sponsorBlockEnabled) {
                        try {
                            let currentVideoId = null;
                            let sponsorSegments = [];
                            
                            function fetchSponsorSegments(videoId) {
                                if (!videoId) return;
                                const prefix = videoId.substring(0, 4);
                                const url = 'https://sponsor.ajay.app/api/skipSegments/' + prefix + '?categories=["sponsor","selfpromo","interaction","intro","outro"]';
                                fetch(url)
                                    .then(res => res.json())
                                    .then(data => {
                                        const videoData = data.find(d => d.videoID === videoId);
                                        if (videoData && videoData.segments) {
                                            sponsorSegments = videoData.segments;
                                        } else {
                                            sponsorSegments = [];
                                        }
                                    }).catch(e => {});
                            }
                            
                            setInterval(() => {
                                const urlParams = new URLSearchParams(window.location.search);
                                const v = urlParams.get('v');
                                if (v && v !== currentVideoId) {
                                    currentVideoId = v;
                                    fetchSponsorSegments(v);
                                }
                            }, 2000);
                            
                            setInterval(() => {
                                const video = document.querySelector('video');
                                if (video && sponsorSegments.length > 0) {
                                    const time = video.currentTime;
                                    sponsorSegments.forEach(seg => {
                                        if (time >= seg.segment[0] && time < seg.segment[1]) {
                                            video.currentTime = seg.segment[1];
                                            if (window.ZeroAdsBridge) {
                                                window.ZeroAdsBridge.postMessage(JSON.stringify({
                                                    type: 'SPONSOR_SKIPPED',
                                                    segment: seg
                                                }));
                                            }
                                        }
                                    });
                                }
                            }, 500);
                        } catch(e) { reportError('SponsorBlock', e); }
                    }
                    
                    // Media Session bridge
                    try {
                        setInterval(() => {
                            const video = document.querySelector('video');
                            if (video) {
                                if (!video.dataset.zeroAdsHooked) {
                                    video.dataset.zeroAdsHooked = 'true';
                                    video.addEventListener('play', () => {
                                        if (window.ZeroAdsBridge) window.ZeroAdsBridge.postMessage(JSON.stringify({type: 'MEDIA_PLAY'}));
                                    });
                                    video.addEventListener('pause', () => {
                                        if (window.ZeroAdsBridge) window.ZeroAdsBridge.postMessage(JSON.stringify({type: 'MEDIA_PAUSE'}));
                                    });
                                    video.addEventListener('loadedmetadata', () => {
                                        if (window.ZeroAdsBridge) window.ZeroAdsBridge.postMessage(JSON.stringify({type: 'MEDIA_META'}));
                                    });
                                }
                            }
                        }, 2000);
                        
                        window.addEventListener('message', (e) => {
                            if (e.data && e.data.type === 'ZEROADS_COMMAND') {
                                const video = document.querySelector('video');
                                if (video) {
                                    if (e.data.command === 'play') video.play();
                                    if (e.data.command === 'pause') video.pause();
                                    if (e.data.command === 'skip_forward') video.currentTime += 10;
                                    if (e.data.command === 'skip_backward') video.currentTime -= 10;
                                }
                            }
                        });
                    } catch(e) { reportError('MediaSession', e); }
                }
                
            })();
        """.trimIndent()
    }
}
