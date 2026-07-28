function configureVideoTags() {
  [...document.getElementsByTagName("video")].forEach((v) => {
    v.setAttribute("preload", "auto");
    v.setAttribute("playsinline", "true");
    v.setAttribute("controls", "true");
    v.setAttribute("controlslist", "nofullscreen nodownload noremoteplayback");

    if (v.classList.contains("article__video-autoplay--looped")) {
      v.setAttribute("loop", "true");
      v.play();
    }
  });
}

function addImageClickListeners() {
  const images = [...document.getElementsByTagName("img")].filter(
    (img) => !img.classList.contains("iframe-embed__image"),
  );

  images.forEach((img, index) => {
    img.addEventListener("click", (e) => {
      if (img.classList.contains("image-error")) {
        return;
      }

      const galleryNodes = [...document.getElementsByTagName("img")].filter(
        (candidate) =>
          !candidate.classList.contains("iframe-embed__image") &&
          !candidate.classList.contains("image-error"),
      );

      const imageIndex = galleryNodes.indexOf(img);
      if (imageIndex < 0) {
        return;
      }

      /** @type {MediaItem[]} */
      const galleryImages = galleryNodes.map((node) => ({
        url: node.src,
        altText: node.alt || null,
      }));

      e.preventDefault();
      Android.openImageGallery(JSON.stringify(galleryImages), imageIndex);
    });

    longPress(img, (e) => {
      if (img.classList.contains("image-error")) {
        return;
      }

      e.preventDefault();
      Android.showImageDialog(img.src);
    });
  });
}

const IMAGE_ERROR_PLACEHOLDER =
  "data:image/svg+xml;charset=UTF-8," +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 360" fill="none"><rect width="640" height="360" rx="24" fill="#E6EAF0"/><circle cx="210" cy="136" r="34" fill="#A0AAB8"/><path d="M120 280 250 164l86 78 70-56 114 94H120Z" fill="#8B95A5"/><path d="M166 84h308" stroke="#A0AAB8" stroke-width="24" stroke-linecap="round"/></svg>',
  );

function firstNonBlank(values) {
  return values
    .map((value) => value?.trim())
    .find((value) => value && value.length > 0);
}

function firstSrcsetUrl(srcset) {
  if (!srcset) {
    return null;
  }

  return srcset
    .split(",")
    .map((part) => part.trim().split(/\s+/)[0])
    .find((value) => value && value.length > 0);
}

function resolveImageUrl(url, baseUrl) {
  if (!url) {
    return null;
  }

  if (
    url.startsWith("data:") ||
    url.startsWith("blob:") ||
    url.startsWith("file:") ||
    url.startsWith("content:")
  ) {
    return url;
  }

  try {
    return new URL(url, baseUrl || document.baseURI).href;
  } catch (e) {
    return null;
  }
}

function normalizeSrcset(srcset, baseUrl) {
  return srcset
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => {
      const [url, ...descriptor] = part.split(/\s+/);
      const resolvedUrl = resolveImageUrl(url, baseUrl) || url;
      return [resolvedUrl, ...descriptor].join(" ").trim();
    })
    .join(", ");
}

function imageSource(img) {
  const picture = img.parentElement?.nodeName === "PICTURE" ? img.parentElement : null;

  const pictureSource = picture
    ? firstNonBlank(
        [...picture.querySelectorAll("source")].map(
          (source) =>
            firstNonBlank([
              source.getAttribute("data-src"),
              source.getAttribute("src"),
            ]) ||
            firstSrcsetUrl(source.getAttribute("data-srcset")) ||
            firstSrcsetUrl(source.getAttribute("srcset")),
        ),
      )
    : null;

  return (
    pictureSource ||
    firstNonBlank([
      img.getAttribute("data-src"),
      img.getAttribute("data-original"),
      img.getAttribute("data-lazy-src"),
      img.getAttribute("data-url"),
      img.getAttribute("data-image"),
      img.getAttribute("data-cfsrc"),
      img.getAttribute("src"),
    ]) ||
    firstSrcsetUrl(img.getAttribute("data-srcset")) ||
    firstSrcsetUrl(img.getAttribute("srcset"))
  );
}

function imageSrcset(img) {
  const picture = img.parentElement?.nodeName === "PICTURE" ? img.parentElement : null;

  return (
    (picture
      ? firstNonBlank(
          [...picture.querySelectorAll("source")].map((source) =>
            firstNonBlank([
              source.getAttribute("data-srcset"),
              source.getAttribute("srcset"),
            ]),
          ),
        )
      : null) ||
    firstNonBlank([
      img.getAttribute("data-srcset"),
      img.getAttribute("srcset"),
    ])
  );
}

function normalizePictureSources(content, baseUrl) {
  content.querySelectorAll("picture source").forEach((source) => {
    const src = firstNonBlank([
      source.getAttribute("data-src"),
      source.getAttribute("src"),
    ]);
    if (src) {
      const resolvedSrc = resolveImageUrl(src, baseUrl);
      if (resolvedSrc) {
        source.src = resolvedSrc;
      }
    }

    const srcset = firstNonBlank([
      source.getAttribute("data-srcset"),
      source.getAttribute("srcset"),
    ]);
    if (srcset) {
      source.srcset = normalizeSrcset(srcset, baseUrl);
    }
  });
}

function normalizeImageSource(img, baseUrl) {
  const src = imageSource(img);
  if (!src) {
    return false;
  }

  const resolvedSrc = resolveImageUrl(src, baseUrl);
  if (!resolvedSrc) {
    return false;
  }

  delete img.dataset.placeholderImage;
  img.classList.remove("image-error", "loaded");
  img.src = resolvedSrc;

  const srcset = imageSrcset(img);
  if (srcset) {
    img.srcset = normalizeSrcset(srcset, baseUrl);
  } else {
    img.removeAttribute("srcset");
  }

  return true;
}

/**
 * @param {HTMLImageElement} img
 */
function setupImageLoadHandler(img) {
  const markLoaded = () => {
    img.classList.add("loaded");

    if (img.dataset.placeholderImage !== "true") {
      img.classList.remove("image-error");
    }
  };

  const markError = () => {
    if (img.dataset.placeholderImage === "true") {
      img.classList.add("loaded", "image-error");
      return;
    }

    img.dataset.placeholderImage = "true";
    img.classList.add("loaded", "image-error");
    img.removeAttribute("srcset");
    img.src = IMAGE_ERROR_PLACEHOLDER;
  };

  img.onload = markLoaded;
  img.onerror = () => {
    img.onerror = null;
    markError();
  };

  if (img.complete) {
    if (img.naturalWidth > 0) {
      markLoaded();
    } else {
      markError();
    }
  }
}

function addImageLoadListeners() {
  [...document.getElementsByTagName("img")].forEach(setupImageLoadHandler);
}

function observeImages() {
  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      mutation.addedNodes.forEach((node) => {
        if (node.nodeName === "IMG") {
          setupImageLoadHandler(/** @type {HTMLImageElement} */ (node));
        } else if (/** @type {Element} */ (node).querySelectorAll) {
          /** @type {Element} */
          (node).querySelectorAll("img").forEach(setupImageLoadHandler);
        }
      });
    });
  });

  observer.observe(document.body, {
    childList: true,
    subtree: true,
  });
}

function addEmbedListeners() {
  [...document.querySelectorAll("div.iframe-embed")].forEach((div) => {
    div.addEventListener("click", () => {
      const iframe = document.createElement("iframe");
      iframe.src = div.getAttribute("data-iframe-src") || "";
      div.replaceWith(iframe);
    });
  });

  [...document.querySelectorAll("a")].forEach((anchor) => {
    longPress(anchor, () => {
      Android.showLinkDialog(anchor.href, anchor.text);
    });
  });
}

/**
 * @param {HTMLDivElement | Document} element
 * @returns boolean
 */
function cleanEmbeds(element = document) {
  const imgs = element.querySelectorAll("img");

  for (const img of imgs) {
    if (!normalizeImageSource(img, "")) {
      img.remove();
    }
  }

  const embeds = element.querySelectorAll("iframe");

  for (const embed of embeds) {
    const src = embed.getAttribute("src");
    if (!src) {
      continue;
    }

    const youtubeID = findYouTubeMatch(src);

    if (youtubeID !== null) {
      swapPlaceholder(embed, src, youtubeID);
    }
  }

  addEmbedListeners();
}

/**
 * @param {string} src
 */
function findYouTubeMatch(src) {
  for (const regex of YOUTUBE_DOMAINS) {
    const match = src.match(regex);
    if (match) {
      return match[1];
    }
  }
  return null;
}

/**
 * @param {HTMLIFrameElement} embed
 * @param {string} src
 * @param {string} youtubeID
 */
function swapPlaceholder(embed, src, youtubeID) {
  const placeholderImage = document.createElement("img");
  placeholderImage.classList.add("iframe-embed__image", "mercury-parser-keep");
  placeholderImage.setAttribute("src", imageURL(youtubeID));

  const playButton = document.createElement("div");
  playButton.classList.add("iframe-embed__play-button");

  const placeholder = document.createElement("a");
  placeholder.classList.add("iframe-embed");
  placeholder.setAttribute(
    "href",
    `https://www.youtube.com/watch?v=${youtubeID}`,
  );
  placeholder.appendChild(placeholderImage);
  placeholder.appendChild(playButton);

  embed.replaceWith(placeholder);
}

/** @param {string} id */
function imageURL(id) {
  return `https://img.youtube.com/vi/${id}/hqdefault.jpg`;
}

/**
 * @param {string} src
 * @returns string
 */
function autoplaySrc(src) {
  try {
    const url = new URL(src);
    url.searchParams.set("autoplay", "1");
    return url.toString();
  } catch (e) {
    return src;
  }
}

const YOUTUBE_DOMAINS = [
  /.*?\/\/www\.youtube-nocookie\.com\/embed\/(.*?)(\?|$)/,
  /.*?\/\/www\.youtube\.com\/embed\/(.*?)(\?|$)/,
  /.*?\/\/www\.youtube\.com\/user\/.*?#\w\/\w\/\w\/\w\/(.+)\b/,
  /.*?\/\/www\.youtube\.com\/v\/(.*?)(#|\?|$)/,
  /.*?\/\/www\.youtube\.com\/watch\?(?:.*?&)?v=([^&#]*)(?:&|#|$)/,
  /.*?\/\/youtube-nocookie\.com\/embed\/(.*?)(\?|$)/,
  /.*?\/\/youtube\.com\/embed\/(.*?)(\?|$)/,
  /.*?\/\/youtu\.be\/(.*?)(\?|$)/,
];

/**
 * Post-process article content: clean styles, resolve image URLs, wrap tables
 * @param {string} baseUrl
 * @param {boolean} hideImages
 */
function postProcessContent(baseUrl, hideImages) {
  const content = document.getElementById("article-body-content");
  if (!content) return;

  content.querySelectorAll("style").forEach((el) => {
    el.remove();
  });

  content.querySelectorAll("*").forEach((el) => {
    cleanAttributes(el);
  });

  cleanAttributes(document.body);

  content.querySelectorAll("a[onclick]").forEach((anchor) => {
    anchor.removeAttribute("onclick");
  });

  normalizePictureSources(content, baseUrl);

  content.querySelectorAll("img").forEach((img) => {
    if (hideImages) {
      img.remove();
    } else {
      img.loading = "lazy";
      if (!normalizeImageSource(img, baseUrl)) {
        img.remove();
      }
    }
  });

  content.querySelectorAll("table").forEach((table) => {
    if (table.parentElement?.classList.contains("table__wrapper")) return;
    const wrapper = document.createElement("div");
    wrapper.className = "table__wrapper";
    table.parentNode?.insertBefore(wrapper, table);
    wrapper.appendChild(table);
  });
}

/**
 * @param {Element} element
 */
function cleanAttributes(element) {
  element.removeAttribute("style");
  element.removeAttribute("bgcolor");
  element.removeAttribute("color");
  element.removeAttribute("background");
}

/**
 * @param {HTMLElement} element
 * @param {(event: Event) => void} callback
 */
function longPress(element, callback) {
  /** @type {number | undefined} */
  let timer;

  const start = (/** @type {Event} */ event) => {
    timer = setTimeout(() => {
      callback(event);
    }, 500);
  };

  const stop = () => {
    clearTimeout(timer);
  };

  element.addEventListener("mousedown", start);
  element.addEventListener("mouseup", stop);
  element.addEventListener("mouseleave", stop);

  element.addEventListener("touchstart", start);
  element.addEventListener("touchend", stop);
  element.addEventListener("touchcancel", stop);
}

window.addEventListener("DOMContentLoaded", () => {
  cleanEmbeds();
});

/**
 * @param {MessageEvent} event
 */
function handleBlueskyEmbedResize(event) {
  if (event.origin !== "https://embed.bsky.app") return;
  if (!event.data || typeof event.data.height !== "number") return;

  document.querySelectorAll("iframe").forEach((iframe) => {
    if (iframe.contentWindow === event.source) {
      iframe.style.height = event.data.height + "px";
    }
  });
}

window.addEventListener("message", handleBlueskyEmbedResize);

/** @type {string | null} */
let currentPlayingUrl = null;

/** @type {boolean} */
let isCurrentlyPlaying = false;

/**
 * Toggle audio playback - play if stopped, pause if playing
 * @param {string} url
 * @param {string} title
 * @param {string} feedName
 * @param {number | null} durationSeconds
 * @param {string} artworkUrl
 */
function playAudio(url, title, feedName, durationSeconds, artworkUrl) {
  if (currentPlayingUrl === url && isCurrentlyPlaying) {
    Android.pauseAudio();
  } else {
    const audioData = JSON.stringify({
      url: url,
      title: title,
      feedName: feedName,
      durationSeconds: durationSeconds,
      artworkUrl: artworkUrl || null,
    });
    Android.openAudioPlayer(audioData);
  }
}

/**
 * Update the play/pause state for an audio enclosure
 * Called from Android when play state changes
 * @param {string} url
 * @param {boolean} isPlaying
 */
function updateAudioPlayState(url, isPlaying) {
  currentPlayingUrl = url;
  isCurrentlyPlaying = isPlaying;

  const enclosures = document.querySelectorAll(".audio-enclosure");
  enclosures.forEach((enclosure) => {
    const enclosureUrl = enclosure.getAttribute("data-url");
    const playButton = enclosure.querySelector(".audio-enclosure__play-button");
    if (!playButton) return;

    if (enclosureUrl === url) {
      playButton.classList.toggle("playing", isPlaying);
    } else {
      playButton.classList.remove("playing");
    }
  });
}

/**
 * Reset all audio enclosures to not playing state
 * Called from Android when audio is dismissed
 */
function resetAudioPlayState() {
  currentPlayingUrl = null;
  isCurrentlyPlaying = false;
  const playButtons = document.querySelectorAll(
    ".audio-enclosure__play-button",
  );
  playButtons.forEach((btn) => {
    btn.classList.remove("playing");
  });
}

window.onload = () => {
  addImageClickListeners();
  addImageLoadListeners();
  observeImages();
  addEmbedListeners();
  configureVideoTags();
  Android.requestAudioState();
};
