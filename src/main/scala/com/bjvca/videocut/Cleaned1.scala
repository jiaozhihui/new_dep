package com.bjvca.videocut

import java.util.Properties

import com.alibaba.fastjson.{JSON, JSONArray, JSONObject}
import com.bjvca.commonutils.ConfUtils
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.elasticsearch.spark._
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
 * 主要的标签合并方式
 * 合并相邻的相同标签，扩大标签时长，根据重合部分裁剪合并
 *
 */

/**
 * 注意：因为json存es时的原生问题，需要在es中设置
 * PUT /videocut_cleaned/_settings { "index.mapping.total_fields.limit": 5000 }
 * 不要会报异常，字段数量超出限制
 */

object Cleaned1 extends Logging {

  //  def fun(getVid: Int): Unit = {
  def main(args: Array[String]): Unit = {

    val properties = new Properties()
    properties.put("user", "root")
    properties.put("password", "root")

    logWarning("VideoCutMain开始运行")


    val confUtil = new ConfUtils("application.conf")
    //    val confUtil = new ConfUtils("线上application.conf")

    // 创建sparkSession
    val spark: SparkSession = SparkSession.builder()
      .appName("VideoCutMain")
      .master("local[*]")
      .config("spark.debug.maxToStringFields", "300")
      .getOrCreate()
    SparkSession.clearDefaultSession()

    // 读取将要用到的表
    // 1.recognition2_behavior
    spark.read.format("jdbc")
      //      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/ssp_db?characterEncoding=utf-8&useSSL=false",
      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.adseatMysqlUser,
        "password" -> confUtil.adseatMysqlPassword,
        "dbtable" -> "recognition2_behavior"
      ))
      .load()
      .createOrReplaceTempView("recognition2_behavior")
    // 2.recognition2_face
    spark.read.format("jdbc")
      //      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/ssp_db?characterEncoding=utf-8&useSSL=false",
      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.adseatMysqlUser,
        "password" -> confUtil.adseatMysqlPassword,
        "dbtable" -> "recognition2_face"
      ))
      .load()
      .createOrReplaceTempView("recognition2_face")
    // 3.recognition2_object
    spark.read.format("jdbc")
      //      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/ssp_db?characterEncoding=utf-8&useSSL=false",
      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.adseatMysqlUser,
        "password" -> confUtil.adseatMysqlPassword,
        "dbtable" -> "recognition2_object"
      ))
      .load()
      .createOrReplaceTempView("recognition2_object")
    // 4.recognition2_scene
    spark.read.format("jdbc")
      //      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/ssp_db?characterEncoding=utf-8&useSSL=false",
      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.adseatMysqlUser,
        "password" -> confUtil.adseatMysqlPassword,
        "dbtable" -> "recognition2_scene"
      ))
      .load()
      .createOrReplaceTempView("recognition2_scene")
    // 5.class
    spark.read.format("jdbc")
      //      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/ssp_db?characterEncoding=utf-8&useSSL=false",
      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.adseatMysqlUser,
        "password" -> confUtil.adseatMysqlPassword,
        "dbtable" -> "recognition2_class"
      ))
      .load()
      .createOrReplaceTempView("recognition2_class")

    // 6.kukai_original_video
    spark.read.format("jdbc")
      //      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/ssp_db?characterEncoding=utf-8&useSSL=false",
      .options(Map("url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.adseatMysqlUser,
        "password" -> confUtil.adseatMysqlPassword,
        "dbtable" -> "kukai_original_video"
      ))
      .load()
      .createOrReplaceTempView("kukai_original_video")


    // 从mysql拿到数据，转化为json
    import spark.implicits._

    /**
     * 从广告位拿到可用的广告位列表
     */
    // TODO 拿到可播放视频
    spark.read.format("jdbc")
      .options(Map(
        "url" -> s"jdbc:mysql://${confUtil.adseatMysqlHost}:3306/video?characterEncoding=utf-8&useSSL=false",
        "driver" -> "com.mysql.jdbc.Driver",
        "user" -> confUtil.videocutMysqlUser,
        "password" -> confUtil.videocutMysqlPassword,
        "dbtable" -> "kukai_original_video"
      )).load()
      .select($"videoId" as "video_id", $"originalUrl" as "media_addr")
      .createOrReplaceTempView("bbb")

    // 拿到全部广告位数据
    // 视频id、视频名、
    // 开始时间、结束时间
    // drama_name（剧集分类）, drama_type_name（剧集类型）
    // media_area_name（地区名）, media_release_data（上映年份）
    // 二级标签name
    // 一级标签id（分类用）、三级标签name
    //      .select($"video_id", $"media_name",
    //      $"ad_seat_b_time", $"ad_seat_e_time",
    //      $"drama_name", $"drama_type_name",
    //      $"media_area_name", $"media_release_date",
    //      $"class2_name",
    //      $"class_type_id", $"class3_name",
    //      $"ad_seat_img")
    //      .createOrReplaceTempView("aaa")

    // TODO 1.拿到所有的广告位 aaa
    spark.sql(
      """
        |select recognition2_behavior.media_id video_id,
        |       kukai_original_video.videoName media_name,
        |       recognition2_behavior.time_start ad_seat_b_time,
        |       recognition2_behavior.time_end ad_seat_e_time,
        |       kukai_original_video.category drama_name,
        |       kukai_original_video.classify drama_type_name,
        |       kukai_original_video.area media_area_name,
        |       kukai_original_video.releaseTime media_release_date,
        |       recognition2_class.class1_name class2_name,
        |       recognition2_class.class_type class_type_id,
        |       recognition2_class.class2_name class3_name,
        |       recognition2_behavior.object_img ad_seat_img
        |from recognition2_behavior
        |join recognition2_class
        |    on recognition2_behavior.class_id = recognition2_class.class_id
        |join kukai_original_video
        |on kukai_original_video.videoId = media_id
        |union all
        |select recognition2_face.media_id video_id,
        |       kukai_original_video.videoName media_name,
        |       recognition2_face.time_start ad_seat_b_time,
        |       recognition2_face.time_end ad_seat_e_time,
        |       kukai_original_video.category drama_name,
        |       kukai_original_video.classify drama_type_name,
        |       kukai_original_video.area media_area_name,
        |       kukai_original_video.releaseTime media_release_date,
        |       recognition2_class.class1_name class2_name,
        |       recognition2_class.class_type class_type_id,
        |       recognition2_class.class2_name class3_name,
        |       recognition2_face.object_img ad_seat_img
        |from recognition2_face
        |join recognition2_class
        |    on recognition2_face.class_id = recognition2_class.class_id
        |join kukai_original_video
        |on kukai_original_video.videoId = media_id
        |union all
        |select recognition2_object.media_id video_id,
        |       kukai_original_video.videoName media_name,
        |       recognition2_object.time_start ad_seat_b_time,
        |       recognition2_object.time_end ad_seat_e_time,
        |       kukai_original_video.category drama_name,
        |       kukai_original_video.classify drama_type_name,
        |       kukai_original_video.area media_area_name,
        |       kukai_original_video.releaseTime media_release_date,
        |       recognition2_class.class1_name class2_name,
        |       recognition2_class.class_type class_type_id,
        |       recognition2_class.class2_name class3_name,
        |       recognition2_object.object_img ad_seat_img
        |from recognition2_object
        |join recognition2_class
        |    on recognition2_object.class_id = recognition2_class.class_id
        |join kukai_original_video
        |on kukai_original_video.videoId = media_id
        |union all
        |select recognition2_scene.media_id video_id,
        |       kukai_original_video.videoName media_name,
        |       recognition2_scene.time_start ad_seat_b_time,
        |       recognition2_scene.time_end ad_seat_e_time,
        |       kukai_original_video.category drama_name,
        |       kukai_original_video.classify drama_type_name,
        |       kukai_original_video.area media_area_name,
        |       kukai_original_video.releaseTime media_release_date,
        |       recognition2_class.class1_name class2_name,
        |       recognition2_class.class_type class_type_id,
        |       recognition2_class.class2_name class3_name,
        |       recognition2_scene.object_img ad_seat_img
        |from recognition2_scene
        |join recognition2_class
        |    on recognition2_scene.class_id = recognition2_class.class_id
        |join kukai_original_video
        |on kukai_original_video.videoId = media_id
        |""".stripMargin)
      //      .filter(s"video_id = $video_id")
      .createOrReplaceTempView("aaa")

    // 为后面过滤掉ts格式的视频
    val filterList = spark.sql(
      """
        |SELECT * FROM bbb WHERE media_addr LIKE '%.ts'
        |""".stripMargin)
      .select("video_id")
      .collect()
    val array = filterList.map(_.get(0).toString)

    // 广播出去ts的数组
    val bFilterList = spark.sparkContext.broadcast(array)

    spark.sql(
      """
        |select
        |aaa.video_id,
        |aaa.media_name,
        |aaa.ad_seat_b_time,
        |aaa.ad_seat_e_time,
        |aaa.drama_name,
        |aaa.drama_type_name,
        |aaa.media_area_name,
        |aaa.media_release_date,
        |aaa.class2_name,
        |aaa.class_type_id,
        |aaa.class3_name,
        |aaa.ad_seat_img
        |from aaa join bbb
        |on aaa.video_id=bbb.video_id
        |""".stripMargin)
      .createOrReplaceTempView("ccc")

    spark.sql("cache table ccc")

    val mysqlRDD = spark.sql("select * from ccc")
      .toJSON
      .rdd

    val reduced = mysqlRDD
      .filter(x => {
        //        过滤掉自定义标签
        val key = JSON.parseObject(x).get("class_type_id").toString
        key.equals("1") || key.equals("2") || key.equals("3") || key.equals("4")
      })
      // 过滤掉ts格式的视频
      .filter(x => {
        val vid = JSON.parseObject(x).get("video_id").toString
        !bFilterList.value.contains(vid)
      })

      // 处理数据为json格式，以video_id为key的元组
      .map(x => {
        val jsonArray = new JSONArray()
        val key = JSON.parseObject(x).get("video_id").toString
        val class3Name = JSON.parseObject(x).get("class3_name").toString
        jsonArray.add(x)
        //      key、vid+class3
        //      value 标签信息
        ((key, class3Name), jsonArray)
      })
      // 将同一个video_id的相同标签reduce到一起，数据组成JSONArray
      .reduceByKey((x, y) => {
        for (i <- 0 until y.size()) {
          x.add(y.get(i))
        }
        x
      })

      /**
       * 预处理逻辑
       * 1、将所有的起止点，前后各扩展4秒
       * 2、根据vid和class3Name分组，将一样的分到一个组，
       * 3、拓展后相邻的相同标签合并在一起
       *
       */
      .map(x => {
        val vid = x._1
        val adseatJsonArray = x._2

        // 将时间点增大前后各四秒

        val adseatList = adseatJsonArray.toArray.map(adseatjson => {
          val nObject = JSON.parseObject(adseatjson.toString)

          val oldbtime = nObject.get("ad_seat_b_time").toString.toLong
          val oldetime = nObject.get("ad_seat_e_time").toString.toLong

          val newbtime = if (oldbtime.toLong - 4000 < 0) 0
          else oldbtime.toLong - 4000

          val newetime = oldetime.toLong + 4000

          nObject.put("ad_seat_b_time", newbtime.toString)
          nObject.put("ad_seat_e_time", newetime.toString)
          nObject
        }).sortBy(y => y.get("ad_seat_b_time").toString.toLong)

        val resultList = new JSONArray()

        var temp = adseatList(0)
        for (i <- 1 until adseatList.size) {
          val thisseat = adseatList(i)

          if (thisseat.getString("ad_seat_b_time").toLong - temp.getString("ad_seat_e_time").toLong <= 0) {
            val nowetime = thisseat.getString("ad_seat_e_time")

            temp.put("ad_seat_e_time", nowetime)
          } else {
            resultList.add(temp.clone().asInstanceOf[JSONObject])
            temp = thisseat

          }

        }
        resultList.add(temp.clone().asInstanceOf[JSONObject])

        (vid, resultList)
      })

      /**
       * 核心逻辑
       *
       * 对拿到的同一个video_id的一组视频进行处理
       * 将所有标签放到一个adseatMap中
       * 将所有起止点放到一个pointlist中
       * 1、创建一个空的tempMap
       * 2、遍历pointlist
       * 3、每拿到一个起始点，就从将对应的标签放到tempMap中
       * 4、输出resultMap中所有的视频片段到resultlist中
       * 5、每拿到一个终止点，就将对应的标签移除出tempMap
       * 6、重复3-4-5
       * 7、整理得到最终的resultMap
       */
      .map(x => {
        val vid = x._1
        val adseatJsonArray = x._2

        // 广告位的Map
        var adseatMap = mutable.Map[String, JSONObject]()
        // 起止点的List
        var pointList = ListBuffer[(String, JSONObject)]()
        // 缓存当前adseat的tempMap
        var tempMap = mutable.Map[String, JSONObject]()
        // 最终返回的数据resultList
        var resultList = ListBuffer[(String, JSONObject)]()


        // 遍历广告位JSON数组，将数据添加到adseatMap中
        // 遍历广告位数据，将所有起止点放到pointList中
        for (i <- 0 until adseatJsonArray.size()) {
          val jsonObject = JSON.parseObject(adseatJsonArray.get(i).toString)
          val class3Name = jsonObject.get("class3_name").toString
          val bTime = jsonObject.get("ad_seat_b_time").toString
          val eTime = jsonObject.get("ad_seat_e_time").toString

          // key
          val key = bTime + "-" + Random.nextInt(1000)
          adseatMap += (key -> jsonObject)

          // 起始点
          val bObject = new JSONObject
          bObject.put("point_type", "begin")
          bObject.put("adseat_key", key)
          pointList += ((bTime, bObject))
          // 终止点
          val eObject = new JSONObject
          eObject.put("point_type", "end")
          eObject.put("adseat_key", key)
          pointList += ((eTime, eObject))
        }

        val pointList2 = pointList.sortBy(_._1.toInt)

        var beginTime = ""
        var endTime = ""

        // 遍历所有point点，进而增加或减少tempMap中的adseat，进而处理处新片段
        for (i <- 0 until pointList2.size) {
          val (pointTime, thisPoint) = pointList2(i)
          val pointType = thisPoint.get("point_type").toString
          val adseatKey = thisPoint.get("adseat_key").toString

          //如果tempMap是空的，将pointTime赋值到开始时间
          if (tempMap.isEmpty) {
            // 初始化开始时间
            beginTime = pointTime
          } else {
            //设置本批次的结束时间为pointTime
            endTime = pointTime

            // 处理tempMap的数据，然后放到resultList中
            /**
             * 处理tempMap
             */
            // 先造出来一个片段的对象
            val tempJsonObj = new JSONObject()

            // 遍历tempMap，将这个片段内所包含的每个adseat数据处理进thisJsonObj
            val keys = tempMap.keys

            val class3List = new JSONArray()

            val class2List = new JSONArray()

            val classImgList = new JSONArray()

            val class3ToImg = new JSONObject()

            for (key <- keys) {

              val thisObj = tempMap(key)
              val file1 = thisObj.get("video_id").asInstanceOf[Int]
              val file2 = thisObj.get("media_name").asInstanceOf[String]
              val file3 = thisObj.get("drama_name").asInstanceOf[String]
              val file4 = thisObj.get("drama_type_name").asInstanceOf[String]
              val file5 = thisObj.get("media_area_name").asInstanceOf[String]
              val file6 = thisObj.get("media_release_date").toString
              val file7 = thisObj.get("class_type_id").toString
              val file8 = thisObj.get("class3_name").asInstanceOf[String]
              val file9 = thisObj.get("class2_name").asInstanceOf[String]
              val file10 = thisObj.get("ad_seat_img").asInstanceOf[String]

              //            将组合后的标签，前后各拓展3s
              val newbegin =
                if ((beginTime.toLong - 3000L) < 0) 0.toString else (beginTime.toLong - 3000L).toString

              val newend = (endTime.toLong + 3000L).toString

              //            media_name索引字段
              tempJsonObj.put("string_vid", file1)
              tempJsonObj.put("media_name", file2)
              tempJsonObj.put("string_drama_name", file3)
              tempJsonObj.put("string_drama_type_name", file4)
              tempJsonObj.put("string_media_area_name", file5)
              tempJsonObj.put("string_media_release_date", file6)
              tempJsonObj.put("string_time", newbegin + "_" + newend)
              tempJsonObj.put("string_time_long", (newend.toLong - newbegin.toLong).toString)

              class3List.add(file8)
              class2List.add(file9)
              classImgList.add(file10)

              class3ToImg.put(file8, file10)

            }

            tempJsonObj.put("string_class3_list", class3List)

            tempJsonObj.put("string_class2_list", class2List)

            tempJsonObj.put("string_class_img_list", classImgList)

            tempJsonObj.put("string_class3_to_img", class3ToImg)

            tempJsonObj.put("string_frame_img_list", classImgList)

            resultList += ((adseatKey, tempJsonObj))


            // 设置本次结束的时间为下一批次的开始时间
            beginTime = endTime

          }

          // 处理完此point点前的片段后，然后针对此point点对tempMap操作
          if (pointType.equals("begin")) {
            // 如果是起始点，从adseatMap拿到对应数据，放到tempMap中
            tempMap += (adseatKey -> adseatMap(adseatKey))

          } else {
            // 如果是终止点，从tempMap中拿掉对应tempMap
            tempMap -= adseatKey

          }

        }

        // 最终返回resultList
        resultList
      })
      .flatMap(x => x.toArray[(String, JSONObject)])
      // 过滤掉时长小于1000毫秒的
      .filter(x => x._2.asInstanceOf[JSONObject].getString("string_time_long").toLong >= 1000)
//      .map(x => {
//        x._2.toString
//      })

      .map(x => CutBean(x._1,x._2.toString))
      .toDF()
      .write
      .mode(SaveMode.Append)
      .jdbc("jdbc:mysql://localhost:3306/video_cut?serverTimezone=GMT", "videocut", properties)

    //      .saveJsonToEs("videocut_new_cleaned/doc", Map(
    //        "es.index.auto.create" -> "true",
    //        "es.nodes" -> confUtil.adxStreamingEsHost,
    //        "es.port" -> "9200"
    ////                "es.mapping.id" -> ""
    //      ))
  }

}