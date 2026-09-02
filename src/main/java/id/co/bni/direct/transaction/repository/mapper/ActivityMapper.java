package id.co.bni.direct.transaction.repository.mapper;

import id.co.bni.direct.transaction.entity.ActivityRows.ActivityFilter;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Aktivitas Transaksi: TRX_TASK (rewrite) UNION BASE_FT (legacy), scoped to inquiry rights. */
@Mapper
public interface ActivityMapper {

    /** One page, newest first. The filter's offset/size drive OFFSET/FETCH. */
    List<ActivityRow> search(@Param("f") ActivityFilter filter);

    /** Total rows behind the same filter (paging totals). */
    long count(@Param("f") ActivityFilter filter);

    /**
     * One transaction by reference number, scoped like the list: null when it does not
     * exist for this company, or sits on an account the user cannot inquire. The filter
     * carries companyId, userId and refNo; every other field null.
     */
    ActivityRow findByRefNo(@Param("f") ActivityFilter filter);
}
