package ir.persiansocks.app;

import androidx.annotation.NonNull;
import androidx.paging.ListenableFuturePagingSource;
import androidx.paging.PagingSource;
import androidx.paging.PagingState;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.*;

/** Pages the already-ranked immutable references without rewriting Room or encoding JSON. */
public final class ProxyPages extends ListenableFuturePagingSource<Integer,ProxyEndpoint> {
    private final List<ProxyEndpoint> rows;
    public ProxyPages(List<ProxyEndpoint> ranked){rows=Collections.unmodifiableList(new ArrayList<>(ranked));}
    @Override public Integer getRefreshKey(@NonNull PagingState<Integer,ProxyEndpoint> state){
        Integer position=state.getAnchorPosition();return position==null?null:Math.max(0,position-state.getConfig().initialLoadSize/2);
    }
    @NonNull @Override public ListenableFuture<PagingSource.LoadResult<Integer,ProxyEndpoint>> loadFuture(@NonNull PagingSource.LoadParams<Integer> params){
        int start=Math.min(rows.size(),Math.max(0,params.getKey()==null?0:params.getKey()));
        int end=(int)Math.min((long)rows.size(),(long)start+params.getLoadSize());
        return Futures.immediateFuture(new PagingSource.LoadResult.Page<>(new ArrayList<>(rows.subList(start,end)),start==0?null:Math.max(0,start-params.getLoadSize()),end==rows.size()?null:end,start,rows.size()-end));
    }
}
