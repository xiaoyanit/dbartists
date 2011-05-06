<?php

$url = "http://music.douban.com/artists/top20";
$f = new SaeFetchurl();
$content = $f->fetch($url);

if ($content != false) {

    //TOP
    echo "#genre\n";
    echo "TOP\n";

    // artists
    echo "#artists\n";
    $pattern = '@<div class="site_bar"[\s\S]+?.jpg"/>@';
    preg_match_all($pattern, $content, $matches);
    for ($i = 0; $i < count($matches[0]); $i++) {
        $match = $matches[0][$i];

        // artist name
        preg_match('/(alt=")[\s\S]+?(")/', $match, $name);
        $name = preg_replace('/alt="/', '', $name[0]);
        $name = preg_replace('/"/', '', $name);
        echo $name."\n";

        // artist pic
        preg_match('/(src=")[\s\S]+?(")/', $match, $pic);
        $pic = preg_replace('/src="/', '', $pic[0]);
        $pic = preg_replace('/"/', '', $pic);
        echo $pic."\n";

        // artist url
        preg_match('/(href=")[\s\S]+?(")/', $match, $url);
        $url = preg_replace('/href="/', '', $url[0]);
        $url = preg_replace('/"/', '', $url);
        echo $url."\n";
        
    }

}


?>
