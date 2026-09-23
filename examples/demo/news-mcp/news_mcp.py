"""最小新闻 MCP server（stdio）：读 Hacker News Algolia API，免 key。

第 31 节 Demo 二的外部能力（技 §6.4 方式二：自写 MCP server）。
不赌社区 server 还活着——几十行、零外部依赖（仅官方 mcp SDK），命运握在自己手里。
"""
import json
import urllib.request

from mcp.server.fastmcp import FastMCP

mcp = FastMCP("news")


@mcp.tool()
def fetch_tech_news(limit: int = 15) -> str:
    """取 Hacker News 当前首页条目：标题 / 链接 / 热度 / 摘要"""
    url = f"https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage={limit}"
    with urllib.request.urlopen(url, timeout=15) as resp:
        hits = json.load(resp).get("hits", [])
    return json.dumps(
        [{"title": h.get("title"), "url": h.get("url"), "points": h.get("points"),
          "summary": (h.get("story_text") or "")[:160]} for h in hits],
        ensure_ascii=False)


if __name__ == "__main__":
    mcp.run()
