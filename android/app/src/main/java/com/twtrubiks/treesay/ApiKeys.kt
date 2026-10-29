package com.twtrubiks.treesay

/** Claude API key 在畫面上的處理。key 本身怎麼保管見 [ApiKeyStore]。 */
object ApiKeys {
    /** 從信件或筆記貼過來常夾帶換行與空白，key 本身不會有空白，全部拿掉。 */
    fun sanitize(input: String): String = input.filterNot { it.isWhitespace() }

    /** 畫面上只露出結尾 4 碼：認得出是哪一把，旁邊的人也看不到整把。太短的連結尾都不露。 */
    fun masked(key: String): String = if (key.length <= 8) "••••" else "••••" + key.takeLast(4)
}
