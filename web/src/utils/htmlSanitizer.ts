/**
 * 轻量级 HTML 消毒工具。
 *
 * <p>仅允许白名单标签和属性，过滤事件处理器、script/style/iframe 等危险标签，
 * 用于 CMS 富文本内容在 v-html 中安全渲染。不引入 DOMPurify 等外部依赖。</p>
 */

/** 允许的标签白名单。 */
const ALLOWED_TAGS = new Set([
  'p', 'br', 'hr', 'span', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'strong', 'b', 'em', 'i', 'u', 's', 'del', 'ins', 'sub', 'sup',
  'ul', 'ol', 'li', 'dl', 'dt', 'dd',
  'a', 'img',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'caption',
  'blockquote', 'pre', 'code'
])

/** 允许的属性白名单（按标签）。 */
const ALLOWED_ATTRIBUTES: Record<string, Set<string>> = {
  a: new Set(['href', 'title', 'target']),
  img: new Set(['src', 'alt', 'title', 'width', 'height']),
  table: new Set(['border', 'cellpadding', 'cellspacing', 'width']),
  td: new Set(['colspan', 'rowspan', 'width']),
  th: new Set(['colspan', 'rowspan', 'width'])
}

/** 允许的全局属性。 */
const GLOBAL_ATTRIBUTES = new Set(['style', 'class', 'id'])

/** 危险标签：直接移除整棵子树。 */
const DANGEROUS_TAGS = new Set(['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'textarea', 'button'])

/** 安全的 URL 协议。 */
const SAFE_URL_PROTOCOLS = new Set(['http:', 'https:', 'mailto:', 'tel:'])

function isSafeUrl(value: string): boolean {
  if (!value) return false
  const trimmed = value.trim().toLowerCase()
  if (trimmed.startsWith('#')) return true
  try {
    const url = new URL(trimmed, window.location.href)
    return SAFE_URL_PROTOCOLS.has(url.protocol)
  } catch {
    // 相对路径或无法解析的 URL，视为不安全
    return false
  }
}

function sanitizeNode(node: Node): Node | null {
  if (node.nodeType === Node.TEXT_NODE) {
    return document.createTextNode(node.textContent ?? '')
  }
  if (node.nodeType !== Node.ELEMENT_NODE) {
    return null
  }

  const element = node as Element
  const tagName = element.tagName.toLowerCase()

  if (DANGEROUS_TAGS.has(tagName)) {
    return null
  }

  if (!ALLOWED_TAGS.has(tagName)) {
    // 不认识的标签：保留其子节点文本内容，避免丢失信息
    const fragment = document.createDocumentFragment()
    element.childNodes.forEach(child => {
      const sanitized = sanitizeNode(child)
      if (sanitized) fragment.appendChild(sanitized)
    })
    return fragment
  }

  const sanitized = document.createElement(tagName)

  const allowedAttrs = ALLOWED_ATTRIBUTES[tagName]
  for (let i = 0; i < element.attributes.length; i++) {
    const attr = element.attributes.item(i)
    if (!attr) continue
    const name = attr.name.toLowerCase()
    const value = attr.value

    // 过滤事件处理器属性
    if (name.startsWith('on')) continue

    // 过滤 data-/x- 等自定义属性，防止通过自定义属性绕过
    if (name.startsWith('data-') || name.startsWith('x-')) continue

    if (allowedAttrs?.has(name) || GLOBAL_ATTRIBUTES.has(name)) {
      if ((tagName === 'a' && name === 'href') || (tagName === 'img' && name === 'src')) {
        if (isSafeUrl(value)) {
          sanitized.setAttribute(name, value)
        }
      } else {
        sanitized.setAttribute(name, value)
      }
    }
  }

  element.childNodes.forEach(child => {
    const childSanitized = sanitizeNode(child)
    if (childSanitized) sanitized.appendChild(childSanitized)
  })

  return sanitized
}

/**
 * 消毒 HTML 字符串，返回可安全用于 v-html 的字符串。
 *
 * @param html 原始 HTML 字符串
 * @returns 消毒后的 HTML 字符串；空字符串或解析失败时返回空字符串
 */
export function sanitizeHtml(html: string | null | undefined): string {
  if (!html) return ''
  const parser = new DOMParser()
  const doc = parser.parseFromString(html, 'text/html')
  const fragment = document.createDocumentFragment()
  doc.body.childNodes.forEach(child => {
    const sanitized = sanitizeNode(child)
    if (sanitized) fragment.appendChild(sanitized)
  })
  const wrapper = document.createElement('div')
  wrapper.appendChild(fragment)
  return wrapper.innerHTML
}

/**
 * 判断外链 URL 是否安全（仅允许 http:// 或 https:// 协议）。
 *
 * @param url URL 字符串
 * @returns 是否安全
 */
export function isSafeExternalUrl(url: string | null | undefined): boolean {
  if (!url) return false
  const trimmed = url.trim()
  return /^https?:\/\//i.test(trimmed)
}
